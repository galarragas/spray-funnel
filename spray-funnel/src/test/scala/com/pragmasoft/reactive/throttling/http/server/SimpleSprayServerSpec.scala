package com.pragmasoft.reactive.throttling.http.server

import org.specs2.mutable.{Around, Specification}
import com.typesafe.config.ConfigFactory
import akka.actor.{ActorRef, ActorSystem}
import spray.util.Utils
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import org.specs2.specification.Scope
import com.pragmasoft.reactive.throttling.threshold.Frequency
import scala.concurrent.duration.{FiniteDuration, Duration}
import org.specs2.time.NoTimeConversions
import org.specs2.execute.{Result, AsResult}
import spray.client.pipelining._
import com.pragmasoft.reactive.throttling.http.server.HttpServerThrottling._
import scala.concurrent.{ExecutionContext, Await, Future}
import spray.http.{StatusCodes, HttpResponse}
import scala.concurrent.duration._
import com.pragmasoft.reactive.throttling.threshold._
import akka.util.Timeout
import com.pragmasoft.reactive.throttling.http.{RequestThrottlingConfiguration, HttpThrottlingConfiguration}
import scala.util.Try
import ExecutionContext.Implicits.global
import spray.client.UnsuccessfulResponseException
import com.pragmasoft.reactive.throttling.util._

class SimpleSprayServerSpec extends Specification with NoTimeConversions {

  val testConf = ConfigFactory.parseString(
    """
    akka {
      loglevel = INFO
      max-retries = 0
      loggers = ["akka.event.slf4j.Slf4jLogger"]
      log-dead-letters-during-shutdown=off
    }
    """
    //    spray.can {
    //      client {
    //        host-connector {
    //          max-connections = 10
    //        }
    //      }
    //    }

  )

  implicit val TIMEOUT: Timeout = 30 seconds


  "A Throttled Simple Server" should {
    "Serve requests" in new WithStubbedApi( (actor, context) => throttleFrequency(2 every 1.second)(actor)(context) ) {
      callService(1)

      withinTimeout(2 seconds) {
        requestList shouldEqual List(1)
      }
    }

    "Delay requests if frequency is too high" in new WithStubbedApi( (actor, context) => throttleFrequency(3 every 5.second)(actor)(context) ) {
      for(i <- 1 to 5) yield callService(i)

      // You can't tell the actual order of requests
      withinTimeout(2 seconds) {
        requestList.length shouldEqual 3
      }

      // Before the second time frame is ended
      withinTimeout(6 seconds) {
        requestList.length shouldEqual 5
      }
    }

    "Reject requests if queue level is too high" in new WithStubbedApi(
                  (actor, context) =>
                    throttleWithConfig(HttpThrottlingConfiguration(
                      1 every 3.seconds,
                      RequestThrottlingConfiguration(maxQueueSize = 2)
                    )
                )(actor)(context) ) {

      for(i <- 1 to 5) yield callService(i)

      //First request is immediately done
      withinTimeout(2 seconds) {
        requestList.length shouldEqual 1
      }

      //Second is enqueued and done after the first interval is expired
      withinTimeout(3 seconds) {
        requestList.length shouldEqual 2
      }

      //Third is enqueued and done after the SECOND interval is expired
      withinTimeout(3 seconds) {
        requestList.length shouldEqual 3
      }

      //Fourth and fifth are discarded
      withinTimeout(3 seconds) {
        requestList.length shouldEqual 3
      }
    }

    "Return failure response for requests rejected because queue level is too high" in new WithStubbedApi(
      (actor, context) =>
        throttleWithConfig(HttpThrottlingConfiguration(
          1 every 3.seconds,
          RequestThrottlingConfiguration(maxQueueSize = 2)
        )
        )(actor)(context) ) {

      val responses : Seq[Future[String]] = for(i <- 1 to 5) yield callService(i)

      withinTimeout(1 seconds) {
        val completedResponses = responses filter { _.isCompleted }

        // The first request has been successful and the last two failed immediately
        completedResponses.length shouldEqual 3
        val successCount = completedResponses.map( { _.value.get } ).foldLeft(0) {
          case (count: Int, currTryResponse: Try[String]) =>  if(currTryResponse.isSuccess) count + 1 else count
        }

        successCount shouldEqual 1
      }
    }

    "Reject requests if they stay too long in queue" in new WithStubbedApi(
      (actor, context) =>
        throttleWithConfig(HttpThrottlingConfiguration(
          2 every 3.seconds,
          RequestThrottlingConfiguration(expiry = 4.seconds)
        )
        )(actor)(context) ) {

      for(i <- 1 to 5) yield callService(i)

      //First two requests are immediately done
      withinTimeout(2 seconds) {
        requestList.length shouldEqual 2
      }
      //Second couple is enqueued and done after the first interval is expired
      withinTimeout(4 seconds) {
        requestList.length shouldEqual 4
      }
      //Fifth is never done because it expires
      withinTimeout(4 seconds) {
        requestList.length shouldEqual 4
      }
    }

    "Return failure for requests expired because they were too long in queue" in new WithStubbedApi(
      (actor, context) =>
        throttleWithConfig(HttpThrottlingConfiguration(
          2 every 3.seconds,
          RequestThrottlingConfiguration(expiry = 4.seconds)
        )
        )(actor)(context) ) {

      val responses = for(i <- 1 to 5) yield callService(i)

      //First two requests are immediately done
      withinTimeout(2 seconds) {
        (responses filter { _.isCompleted }).length shouldEqual 2
        (responses filter { _.isCompleted }) forall { _.value.get.isSuccess } shouldEqual true
      }

      //Second couple is enqueued and done after the first interval is expired
      withinTimeout(4 seconds) {
        (responses filter { _.isCompleted }).length shouldEqual 4
        (responses filter { _.isCompleted }) forall { _.value.get.isSuccess } shouldEqual true
      }

      // The expiration is detected just when a new request is received
      // 4 success and 1 failure
      callService(100)
      withinTimeout(4 seconds) {
        responses forall { _.isCompleted } shouldEqual true
        (responses filter { _.value.get.isSuccess }).length shouldEqual 4
      }
    }.pendingUntilFixed("Flaky test with random failures")


    "Throttle parallel requests" in new WithStubbedApi(
        throttlingWrappingFactory = (actor, context) => throttleFrequencyAndParallelRequest(
          frequencyThreshold = 3 every 3.seconds,
          maxParallelRequests = 2
        )(actor)(context),
        serverResponseDelay = 3.seconds
      ) {
      for(i <- 1 to 4) yield callService(i)

      withinTimeout(3 seconds) {
        // Can only perform 'maxParallelRequests' requests because of the delay
        requestList.length shouldEqual 2
      }

      withinTimeout(4 seconds) {
        requestList.length shouldEqual 4
      }
    }

    "Fail with timeout error if server is slower than configured timeout" in new WithStubbedApi(
        throttlingWrappingFactory = (actor, context) =>
          throttleWithConfig(HttpThrottlingConfiguration(
            10 every 3.seconds,
            RequestThrottlingConfiguration(timeout = 2 seconds)
          ))(actor)(context),
        serverResponseDelay = 3.seconds
      ) {

      // All responses should fail
      // I CAN'T TELL SPRAY TO DO MORE THAN 4 PARALLEL CONNECTIONS TO THE SERVER SO I'M TESTING WITH ONLY 4 REQUESTS
      val responses : Seq[Future[String]] = for(i <- 1 to 4) yield callService(i)

      withinTimeout(4 seconds) {
        responses forall { _.isCompleted } shouldEqual true

        val failureCount = responses.map( { _.value.get } ).foldLeft(0) {
          case (count: Int, currTryResponse: Try[String]) =>  if(currTryResponse.isFailure) count + 1 else count
        }

        failureCount shouldEqual responses.length
      }
    }
  }

  import com.pragmasoft.reactive.throttling.util.stubserver._

  class WithStubbedApi(throttlingWrappingFactory: (ActorRef, ActorSystem)  => ActorRef, serverResponseDelay: FiniteDuration = 0 millis) extends Around with StubServerSupport {
    override lazy val context = ActorSystem(Utils.actorSystemNameFrom(getClass), testConf)


    def callService(id: Int) : Future[String] = {
      val pipeline = sendReceive(context, context.dispatcher) ~> {
        responseFuture: Future[HttpResponse] =>  responseFuture flatMap {
          response: HttpResponse =>
            if(response.status == StatusCodes.OK) Future.successful(response.entity.asString)
            else Future.failed(new UnsuccessfulResponseException(response.status))
        }
      }

      pipeline { Get(s"http://$interface:$port$servicePath?id=$id") }
    }


    def around[T: AsResult](t: => T): Result = {

      val (interface, port) = Utils.temporaryServerHostnameAndPort()

      setupForServerTesting(interface, port, serverResponseDelay, throttlingWrappingFactory)
      try {
        AsResult(t)
      } finally {
        shutdown()

        context.shutdown()
      }
    }

  }


}
