package com.pragmasoft.reactive.throttling.issues.server

import akka.actor.ActorSystem
import akka.testkit.{TestKitBase, TestProbe, TestKit}
import com.pragmasoft.reactive.throttling.http.server.HttpServerThrottling._
import com.pragmasoft.reactive.throttling.http.server.WithStubbedApi
import com.pragmasoft.reactive.throttling.util._
import com.typesafe.config.ConfigFactory
import org.specs2.SpecificationLike
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions
import spray.http.{ContentTypes, ContentType}
import com.pragmasoft.reactive.throttling.threshold._
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, _}
import spray.routing.Directives
import spray.routing.Route

class Issue7ChunkedRequestNotSupported extends Specification with NoTimeConversions with Directives { //with RetryExamples {
  implicit val testConf = ConfigFactory.parseString(
    """
akka {
      loglevel = INFO
      loggers = ["akka.testkit.TestEventListener"]
      log-dead-letters-during-shutdown=off
            log-config-on-start = off
            # event-stream = on
            receive = on
}

spray.can {
  client {
    user-agent-header = spray-can
    idle-timeout = 60 s
    request-timeout = 60 s
  }
}
    """)


  def chunkedRoute(system : ActorSystem): Route = {
    implicit val _ = system

    path("chunked") {
      autoChunk(10) {
        complete { "testchunk1testchunk2testchunk3testchunk4testchunk5" }
      }
    }
  }

  "A throttled client" should {
    "handle chunked responses from server" in  new WithStubbedApi(
        (actor, context) => throttleFrequency(2 every 1.second)(actor)(context),
        0.millis,
        Some(chunkedRoute)
      ) {
        val resourceContent = Await.result( callRoute("chunked"), 5.seconds )

        resourceContent shouldEqual "testchunk1testchunk2testchunk3testchunk4testchunk5"
    }
  }


}
