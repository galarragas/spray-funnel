package com.pragmasoft.reactive.throttling.http.client

import akka.actor.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}
import com.pragmasoft.reactive.throttling.threshold._
import org.specs2.matcher.{MustThrownExpectations, FutureMatchers}
import spray.can.Http.RequestTimeoutException
import scala.concurrent.Future
import akka.util.Timeout
import scala.concurrent.duration._
import spray.client.pipelining._
import org.specs2.mutable.{Around, Specification}
import spray.util.Utils
import org.specs2.time.NoTimeConversions
import org.specs2.execute.{Result, AsResult}
import spray.http.HttpResponse
import com.pragmasoft.reactive.throttling.threshold.Frequency
import com.pragmasoft.reactive.throttling.util._


class SimpleClient(serviceAddress: String, frequency: Frequency, parallelRequests: Int, timeout: Timeout)(implicit val actorSystem: ActorSystem) {

  import com.pragmasoft.reactive.throttling.http.client.HttpClientThrottling._

  import actorSystem.dispatcher

  implicit val apiTimeout: Timeout = timeout

  val maxParallelRequestsPipeline = sendReceive(throttleFrequencyAndParallelRequests(frequency, parallelRequests)) ~> readResponse
  val noMaxParallelRequestsPipeline = sendReceive(throttleFrequency(frequency)) ~> readResponse

  def callFakeService(id: Int): Future[String] = noMaxParallelRequestsPipeline {
    Get(s"$serviceAddress?id=$id")
  }

  def callFakeServiceWithMaxParallelRequests(id: Int): Future[String] = maxParallelRequestsPipeline {
    Get(s"$serviceAddress?id=$id")
  }

  def readResponse(httpResponse: Future[HttpResponse]): Future[String] = httpResponse map {
    _.entity.asString
  }
}


class SimpleSprayClientSpec extends Specification with NoTimeConversions with RetryExamples {

  val MAX_FREQUENCY: Frequency = 5 every (15 seconds)
  val MAX_PARALLEL_REQUESTS = 3
  val TIMEOUT: Timeout = MAX_FREQUENCY.interval * 3

  val defaultTestConf = ConfigFactory.parseString(
    """
    spray.can {
      host-connector {
        max-redirects = 5
      }
      server.remote-address-header = on
    }

    akka {
      log-dead-letters-during-shutdown = off
      loglevel = ERROR
      loggers = ["akka.event.slf4j.Slf4jLogger"]
    }
    """)

  val fastTimeoutNoRetryTestConf = ConfigFactory.parseString(
    """
    spray.can {
      client {
        request-timeout = 2 s
      }
      host-connector {
        max-redirects = 5
        max-retries = 0
      }
      server.remote-address-header = on
    }

    akka {
      log-dead-letters-during-shutdown = off
      loglevel = ERROR
      loggers = ["akka.event.slf4j.Slf4jLogger"]
    }
    """)

  "A Spray Client throtteling the sendReceive pipeline" should {
    s"Enqueue requests to do maximum $MAX_FREQUENCY" in new WithStubbedApi {

      val totalRequests = MAX_FREQUENCY.amount * 2
      for {id <- 1 to totalRequests} yield client.callFakeService(id)

      withinTimeout(2 seconds) {
        requestList(TIMEOUT).length shouldEqual MAX_FREQUENCY.amount
      }

      withinTimeout(MAX_FREQUENCY.interval) {
        requestList(TIMEOUT).length shouldEqual totalRequests
      }
    }

    s"Serve a maximun of $MAX_PARALLEL_REQUESTS requests in parallel" in new WithStubbedApi(responseDelay = MAX_FREQUENCY.interval) {
      val totalRequests = MAX_PARALLEL_REQUESTS + 1
      for {id <- 1 to totalRequests} yield client.callFakeServiceWithMaxParallelRequests(id)

      Thread.sleep(MAX_FREQUENCY.interval.toMillis)

      requestList(TIMEOUT).length shouldEqual MAX_PARALLEL_REQUESTS

      withinTimeout(2 seconds) {
        requestList(TIMEOUT).length shouldEqual totalRequests
      }
    }

    "Have no concurrent request threshold for unbounded channels" in new WithStubbedApi(responseDelay = MAX_FREQUENCY.interval) {
      val totalRequests = MAX_PARALLEL_REQUESTS + 1
      for {id <- 1 to totalRequests} yield {
        client.callFakeService(id)
      }

      withinTimeout(1 second) {
        val requests = requestList(TIMEOUT)
        requests.length shouldEqual totalRequests
      }
    }

    "Complete future when spray client times out" in new WithStubbedApi(responseDelay = 1000 seconds, fastTimeoutNoRetryTestConf) with FutureMatchers with MustThrownExpectations {

      val er = client.callFakeServiceWithMaxParallelRequests(1)

      er must throwA[RequestTimeoutException].await(0, 100 seconds)
    }
  }


  import com.pragmasoft.reactive.throttling.util.stubserver._

  class WithStubbedApi(val responseDelay: FiniteDuration = 0 seconds, conf: Config = defaultTestConf) extends Around with StubServerSupport {

    override lazy val context = ActorSystem(Utils.actorSystemNameFrom(getClass), conf)

    var client: SimpleClient = _

    def around[T: AsResult](t: => T): Result = {

      val (interface, port) = Utils.temporaryServerHostnameAndPort()

      client = new SimpleClient(s"http://$interface:$port$servicePath", MAX_FREQUENCY, MAX_PARALLEL_REQUESTS, TIMEOUT)(context)
      setupForClientTesting(interface, port, responseDelay)
      try {
        AsResult(t)
      } finally {
        shutdown()

        context.shutdown()
      }
    }
  }

}
