/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package services

import java.io.File
import java.util.Calendar

import common.{StreamLogging, TestUtils, WhiskProperties, WskActorSystem}
import org.apache.kafka.clients.consumer.CommitFailedException
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner
import whisk.common.TransactionId
import whisk.connector.kafka.{KafkaConsumerConnector, KafkaMessagingProvider, KafkaProducerConnector}
import whisk.core.WhiskConfig
import whisk.core.connector.Message
import whisk.utils.{retry, ExecutionContextFactory}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.postfixOps
import scala.util.Try

@RunWith(classOf[JUnitRunner])
class KafkaConnectorTests extends FlatSpec with Matchers with WskActorSystem with BeforeAndAfterAll with StreamLogging {
  implicit val transid: TransactionId = TransactionId.testing
  implicit val ec: ExecutionContext = ExecutionContextFactory.makeCachedThreadPoolExecutionContext()

  val config = new WhiskConfig(WhiskConfig.kafkaHosts)
  assert(config.isValid)

  val groupid = "kafkatest"
  val topic = "KafkaConnectorTestTopic"
  val maxPollInterval = 10 seconds

  // Need to overwrite replication factor for tests that shut down and start
  // Kafka instances intentionally. These tests will fail if there is more than
  // one Kafka host but a replication factor of 1.
  val kafkaHosts: Array[String] = config.kafkaHosts.split(",")
  val replicationFactor: Int = kafkaHosts.length / 2 + 1
  System.setProperty("whisk.kafka.replication-factor", replicationFactor.toString)
  println(s"Create test topic '$topic' with replicationFactor=$replicationFactor")
  assert(KafkaMessagingProvider.ensureTopic(config, topic, topic), s"Creation of topic $topic failed")

  println(s"Create test topic '${topic}' with replicationFactor=${replicationFactor}")
  assert(KafkaMessagingProvider.ensureTopic(config, topic, topic), s"Creation of topic ${topic} failed")

  val producer = new KafkaProducerConnector(config.kafkaHosts, ec)
  val consumer = new KafkaConsumerConnector(config.kafkaHosts, groupid, topic)

  override def afterAll(): Unit = {
    producer.close()
    consumer.close()
    super.afterAll()
  }

  def commandComponent(host: String, command: String, component: String): TestUtils.RunResult = {
    def file(path: String) = Try(new File(path)).filter(_.exists).map(_.getAbsolutePath).toOption
    val docker = (file("/usr/bin/docker") orElse file("/usr/local/bin/docker")).getOrElse("docker")
    val dockerPort = WhiskProperties.getProperty(WhiskConfig.dockerPort)
    val cmd = Seq(docker, "--host", host + ":" + dockerPort, command, component)

    TestUtils.runCmd(0, new File("."), cmd: _*)
  }

  def sendAndReceiveMessage(message: Message,
                            waitForSend: FiniteDuration,
                            waitForReceive: FiniteDuration): Iterable[String] = {
    retry {
      val start = java.lang.System.currentTimeMillis
      println(s"Send message to topic.")
      val sent = Await.result(producer.send(topic, message), waitForSend)
      println(s"Successfully sent message to topic: $sent")
      println(s"Receiving message from topic.")
      val received = consumer.peek(waitForReceive).map { case (_, _, _, msg) => new String(msg, "utf-8") }
      val end = java.lang.System.currentTimeMillis
      val elapsed = end - start
      println(s"Received ${received.size}. Took $elapsed msec: $received")

      received.last should be(message.serialize)
      received
    }
  }

  def createMessage(): Message = new Message { override val serialize: String = Calendar.getInstance.getTime.toString }

  behavior of "Kafka connector"

  it should "send and receive a kafka message which sets up the topic" in {
    for (i <- 0 until 5) {
      val message = createMessage()
      val received = sendAndReceiveMessage(message, 20 seconds, 10 seconds)
      received.size should be >= 1
      consumer.commit()
    }
  }

  it should "send and receive a kafka message even after session timeout" in {
    // "clear" the topic so there are 0 messages to be read
    sendAndReceiveMessage(createMessage(), 1 seconds, 1 seconds)
    consumer.commit()

    (1 to 2).foreach { i =>
      val message = createMessage()
      val received = sendAndReceiveMessage(message, 1 seconds, 1 seconds)
      received.size shouldBe i // should accumulate since the commits fail

      Thread.sleep((maxPollInterval + 1.second).toMillis)
      a[CommitFailedException] should be thrownBy consumer.commit()
    }

    val message3 = createMessage()
    val received3 = sendAndReceiveMessage(message3, 1 seconds, 1 seconds)
    received3.size shouldBe 2 + 1 // since the last commit still failed
    consumer.commit()

    val message4 = createMessage()
    val received4 = sendAndReceiveMessage(message4, 1 seconds, 1 seconds)
    received4.size shouldBe 1
    consumer.commit()
  }

  if (kafkaHosts.length > 1) {
    it should "send and receive a kafka message even after shutdown one of instances" in {
      kafkaHosts.indices.foreach { i =>
        val message = createMessage()
        val kafkaHost = kafkaHosts(i).split(":")(0)
        val startLog = s", started"
        val prevCount = startLog.r.findAllMatchIn(commandComponent(kafkaHost, "logs", s"kafka$i").stdout).length

        commandComponent(kafkaHost, "stop", s"kafka$i")
        sendAndReceiveMessage(message, 30 seconds, 30 seconds) should have size 1
        consumer.commit()

        commandComponent(kafkaHost, "start", s"kafka$i")
        retry({
          startLog.r
            .findAllMatchIn(commandComponent(kafkaHost, "logs", s"kafka$i").stdout)
            .length shouldBe prevCount + 1
        }, 20, Some(1.second)) // wait until kafka is up

        sendAndReceiveMessage(message, 30 seconds, 30 seconds) should have size 1
        consumer.commit()
      }
    }
  }
}
