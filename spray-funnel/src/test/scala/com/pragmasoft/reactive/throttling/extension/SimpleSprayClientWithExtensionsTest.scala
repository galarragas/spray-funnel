package com.pragmasoft.reactive.throttling.extension

import akka.actor.{ActorSystem, ExtensionKey, ExtendedActorSystem}
import spray.json.DefaultJsonProtocol
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import spray.client.pipelining._
import com.pragmasoft.reactive.throttling.threshold.Frequency
import scala.concurrent.{Await, Future}
import akka.io.IO
import org.scalatest.{Matchers, FlatSpec}
import com.pragmasoft.reactive.throttling.{threshold, extension}
import com.github.tomakehurst.wiremock.client.WireMock._
import org.apache.http.HttpStatus._
import org.apache.http.HttpHeaders._
import scala.util.Try
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration._
import com.pragmasoft.reactive.throttling.threshold.Frequency
import com.github.tomakehurst.wiremock.client.WireMock
import scala.concurrent.duration._
import threshold._

// Both lines have to be there to make spray json conversions work
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

class TestFunneledChannelExtension(val system: ExtendedActorSystem) extends FunneledChannelExtension {
  lazy val configRootName = "qos.channels.channel1"
}

object TestFunneledChannel extends ExtensionKey[TestFunneledChannelExtension]

case class SimpleResponse(message: String)

object SimpleClientProtocol extends DefaultJsonProtocol {
  implicit val simpleResponseFormat = jsonFormat1(SimpleResponse)
}

class SimpleSprayClient(serverBaseAddress: String, timeout : Timeout ) {

  import SimpleClientProtocol._

  implicit val actorSystem = ActorSystem("program-info-client", ConfigFactory.parseResources("test.conf"))
  import actorSystem.dispatcher

  implicit val futureTimeout : Timeout = timeout

  val pipeline = sendReceive(IO(TestFunneledChannel)) ~> unmarshal[SimpleResponse]

  def callFakeService(id: Int) : Future[SimpleResponse] = pipeline { Get(s"$serverBaseAddress/fakeService?$id") }

  def shutdown() = actorSystem.shutdown()
}

class SimpleSprayClientWithExtensionsTest extends FlatSpec with Matchers {
  val port = 29998
  val stubServiceUrl = s"http://localhost:$port"

  // From config file
  val defaultFrequency : Frequency = 5 every (15 seconds)
  val defaultParallelRequests = 3
  val defaultTimeout : Timeout = defaultFrequency.interval * 3

  val fakeServiceRegex: String = """/fakeService\?\d+"""

  behavior of "SimpleSprayClient"

  it should s"enqueue requests to do maximum $defaultFrequency" in withStubbedApi() { client : extension.SimpleSprayClient =>

    givenThat {
      get( urlMatching( fakeServiceRegex ) ) willReturn {
        aResponse withStatus(SC_OK)  withHeader(CONTENT_TYPE, "application/json") withBody( """{ "message": "hello" }""" )
      }
    }

    val totalRequests = defaultFrequency.amount * 2
    for { id <- 0 to totalRequests } yield client.callFakeService(id)

    Thread.sleep(1000)

    verify(defaultFrequency.amount, getRequestedFor( urlMatching( fakeServiceRegex ) ) )

    Thread.sleep(defaultFrequency.interval.toMillis)

    verify(totalRequests, getRequestedFor( urlMatching( fakeServiceRegex ) ) )
  }

  // I have to make the others go to timeout and count the success replies. Can't do better since WireMock will stay
  // waiting to have handled all the responses before executing assertions
  it should "limit parallel requests" in {
    val timeout = (1 seconds)

    withStubbedApi(timeout = timeout) { client : extension.SimpleSprayClient =>

      val responseDelay = (timeout / 2) + (100 millis)
      givenThat {
        get( urlMatching( fakeServiceRegex ) ) willReturn {
          aResponse withStatus(SC_OK)  withHeader(CONTENT_TYPE, "application/json") withBody( """{ "message": "hello" }""" ) withFixedDelay(responseDelay.toMillis.toInt)
        }
      }

      val responseFutures = for { id <- 0 to (defaultParallelRequests * 2) } yield client.callFakeService(id)

      Thread.sleep( defaultFrequency.interval.toMillis )

      val responses = responseFutures map { future => Try { Await.result(future, responseDelay) } }
      val successfulResponses = responses filter { tryResponse : Try[extension.SimpleResponse] => tryResponse.isSuccess }

      // Only the first batch has been successful, the others are timed out
      successfulResponses should have length defaultParallelRequests
    }
  }

  def withStubbedApi(timeout: Timeout = defaultTimeout)( test: extension.SimpleSprayClient => Unit ) = {

    var client : extension.SimpleSprayClient = null
    var wireMockServer : WireMockServer = null

    try {
      client = new extension.SimpleSprayClient(stubServiceUrl, timeout)

      wireMockServer = new WireMockServer(wireMockConfig().port(port));
      wireMockServer.start();

      WireMock.configureFor("localhost", port);

      test(client)

    } finally {
      if(client != null)
        client.shutdown()

      if(wireMockServer != null)
        wireMockServer.stop()
    }

  }
}