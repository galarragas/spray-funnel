package com.pragmasoft.reactive.throttling.issues.client

import java.util.Date

import akka.actor.{ActorLogging, Actor, Props, ActorSystem}
import akka.io.IO
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions
import spray.can.Http
import spray.http.{HttpResponse, Uri, HttpRequest}
import scala.concurrent.Await
import scala.concurrent.duration._
import spray.routing.SimpleRoutingApp
import scala.concurrent.duration.Duration


class Issue7_ChunkedRequestNotSupported extends Specification with NoTimeConversions {
  val testConf = ConfigFactory.parseString(
    """
akka {
      loglevel = DEBUG
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


  implicit val system = ActorSystem("t7sys", testConf)
  import system.dispatcher // execution context for futures below
  val interface = "http://localhost"
  val port = 50000

  val timeout: Duration = 10.seconds

  sequential

  step {
    object server extends SimpleRoutingApp {
      startServer(interface = interface, port = port) {
        path("hello") {
          get {
            path()
            complete {
              <h1>Say hello to spray</h1>
            }
          }
        }
      }
    }

    "A throttled client" should {
      "handle chunked responses from server" in {
        true
      }
    }

  }
  step {
    println("shutting down system")
    TestKit.shutdownActorSystem(system)
  }
}
