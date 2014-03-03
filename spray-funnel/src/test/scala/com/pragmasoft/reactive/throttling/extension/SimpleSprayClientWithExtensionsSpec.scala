package com.pragmasoft.reactive.throttling.extension

import akka.actor.{ActorSystem, ExtensionKey, ExtendedActorSystem}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import spray.client.pipelining._
import scala.concurrent.Future
import akka.io.IO
import com.pragmasoft.reactive.throttling.threshold
import scala.concurrent.duration._
import threshold._
import spray.http.HttpResponse
import org.specs2.mutable.{Around, Specification}
import org.specs2.time.NoTimeConversions
import spray.util.Utils
import org.specs2.execute.{Result, AsResult}


class TestFunneledChannelExtension(val system: ExtendedActorSystem) extends FunneledChannelExtension {
  lazy val configRootName = "qos.channels.channel1"
}

object TestFunneledChannel extends ExtensionKey[TestFunneledChannelExtension]

class SimpleClient(serviceAddress: String, timeout: Timeout)(implicit val actorSystem: ActorSystem) {

  import actorSystem.dispatcher

  implicit val futureTimeout: Timeout = timeout

  val pipeline = sendReceive(IO(TestFunneledChannel)) ~> readResponse

  def callFakeService(id: Int): Future[String] = pipeline {
    Get(s"$serviceAddress?id=$id")
  }

  def shutdown() = actorSystem.shutdown()

  def readResponse(httpResponse: Future[HttpResponse]): Future[String] = httpResponse map {
    _.entity.asString
  }
}

class SimpleSprayClientWithExtensionsSpec extends Specification with NoTimeConversions {
  val MAX_FREQUENCY: Frequency = 5 every (15 seconds)
  val MAX_PARALLEL_REQUESTS = 3
  val TIMEOUT: Timeout = MAX_FREQUENCY.interval * 3


  val testConf = ConfigFactory.parseString(
    s"""
    spray.can {
      host-connector {
        max-redirects = 5
      }
      server.remote-address-header = on
    }

    akka {
      loglevel = ERROR
      loggers = ["akka.event.slf4j.Slf4jLogger"]
    }

    qos.channels {
        channel1 {
            frequency {
                threshold = ${MAX_FREQUENCY.amount}
                interval = ${MAX_FREQUENCY.interval.inSeconds} s
            }
            parallel.requests = $MAX_PARALLEL_REQUESTS
            timeout = ${TIMEOUT.duration.inSeconds} s
        }
    }
    """)

  "A Spray Client using a throttled channel" should {

    s"Enqueue requests to do maximum $MAX_FREQUENCY" in new WithStubbedApi {

      val totalRequests = MAX_FREQUENCY.amount * 2
      for {id <- 0 to totalRequests} yield client.callFakeService(id)

      Thread.sleep(1000)

      requestList(TIMEOUT).length shouldEqual MAX_FREQUENCY.amount

      Thread.sleep(MAX_FREQUENCY.interval.toMillis)

      requestList(TIMEOUT).length shouldEqual totalRequests
    }

    s"Serve a maximun of $MAX_PARALLEL_REQUESTS requests in parallel" in new WithStubbedApi(responseDelay = MAX_FREQUENCY.interval) {
      val totalRequests = MAX_PARALLEL_REQUESTS + 1
      for {id <- 0 to totalRequests} yield client.callFakeService(id)

      Thread.sleep(MAX_FREQUENCY.interval.toMillis)

      requestList(TIMEOUT).length shouldEqual MAX_PARALLEL_REQUESTS

      Thread.sleep(1000)

      requestList(TIMEOUT).length shouldEqual totalRequests
    }
  }

  import com.pragmasoft.reactive.throttling.util.stubserver._

  class WithStubbedApi(val responseDelay: FiniteDuration = 0 seconds) extends Around with StubServerSupport {
    override lazy val context = ActorSystem(Utils.actorSystemNameFrom(getClass), testConf)

    var client: SimpleClient = _

    def around[T: AsResult](t: => T): Result = {

      val (interface, port) = Utils.temporaryServerHostnameAndPort()

      client = new SimpleClient(s"http://$interface:$port$servicePath", TIMEOUT)(context)
      setup(interface, port, responseDelay)
      try {
        AsResult(t)
      } finally {
        if (client != null)
          client.shutdown()

        shutdown()

        context.shutdown()
      }
    }
  }

}