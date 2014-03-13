package com.pragmasoft.reactive.throttling.http.server

import org.specs2.mutable.Specification
import com.typesafe.config.ConfigFactory
import akka.actor.{ActorRef, ActorSystem}
import spray.util.Utils
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import org.specs2.specification.Scope
import com.pragmasoft.reactive.throttling.threshold.Frequency
import scala.concurrent.duration.Duration
import org.specs2.time.NoTimeConversions

class SimpleSprayServerSpec extends Specification with NoTimeConversions {

  val testConf = ConfigFactory.parseString(
    """
    akka {
      loglevel = INFO
      loggers = ["akka.event.slf4j.Slf4jLogger"]
    }
    """)

  val system = ActorSystem(Utils.actorSystemNameFrom(getClass), testConf)

  abstract class ActorTestScope(actorSystem: ActorSystem) extends TestKit(actorSystem) with ImplicitSender with Scope {


  }

  "A Throttled Simple Server should" should {
    "Serve requests" in new ActorTestScope(system) {

    }

    "Delay requests if frequency is too high" in new ActorTestScope(system) {

    }

    "Reject requests if frequency is too high" in new ActorTestScope(system) {

    }
  }

}
