package com.pragmasoft.reactive.throttling.issues.client

import scala.concurrent.Await._
import scala.util.{ Success, Failure }
import akka.testkit.{ ImplicitSender, TestProbe, TestKit }
import akka.actor.{ ActorRefFactory, Props, ActorRef, ActorSystem }
import scala.concurrent.duration._
import spray.http.HttpRequest
import spray.client.pipelining._
import com.pragmasoft.reactive.throttling.actors.handlerspool.{ SetActorPool, ActorPool }
import org.mockito.Mockito._
import org.mockito.Matchers._
import akka.testkit.TestActor.AutoPilot
import com.pragmasoft.reactive.throttling.threshold._
import org.specs2.specification.Scope
import org.specs2.time.NoTimeConversions
import org.specs2.mutable.Specification
import org.specs2.mock._
import com.typesafe.config.ConfigFactory
import com.pragmasoft.reactive.throttling.http.{ DiscardReason, DiscardedClientRequest }
import DiscardReason._
import com.pragmasoft.reactive.throttling.http.DiscardedClientRequest
import scala.util.{ Failure, Try }
import com.pragmasoft.reactive.throttling.actors.RequestReplyThrottlingCoordinator
import com.pragmasoft.reactive.throttling.actors.ClientRequest
import org.specs2.time.NoTimeConversions
import com.pragmasoft.reactive.throttling.actors.Ready
import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import scala.util.Random
import spray.can.Http
import spray.httpx.encoding.Gzip
import spray.http._
import akka.io.IO
import java.util.Date
import scala.concurrent.{Await, Future}
import akka.testkit.TestActor
import com.pragmasoft.reactive.throttling.http.client.extension.FunneledChannelExtension
import com.pragmasoft.reactive.throttling.http.FailedClientRequest

class SaasFunneledChannelExtension(val system: ExtendedActorSystem) extends FunneledChannelExtension {
  lazy val configRootName = "qos.channels.saas"
}

object SaasFunneledChannel extends ExtensionKey[SaasFunneledChannelExtension]

class DiscardedClientRequestNotGeneratedSpec extends Specification with NoTimeConversions {
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

qos.channels {
    saas {
        frequency {
            threshold = 1
            interval = 1 s
        }
        requests {
            parallel-threshold = 3
            timeout = 10 s
            expiry = infinite
            max-queue-size = 1
        }
    }
}    """)

  implicit val system = ActorSystem("tsys", testConf)
  import system.dispatcher // execution context for futures below
  val interface = "http://localhost"
  val port = 50000

  val timeout: Duration = 10.seconds

  sequential

  step {
    import system.dispatcher
    /* this is to simulate a pretty slow service */
    val testService = system.actorOf(Props(new Actor with ActorLogging {
      val serviceLag = 1.seconds
      var startTimestamp = System.currentTimeMillis()
      var requests = 0
      def receive = {
        case _: Http.Connected ⇒ sender ! Http.Register(self)
        case HttpRequest(_, Uri.Path("/"), _, _, _) ⇒
          requests += 1
          log.info(s"received $requests requests after ${(System.currentTimeMillis() - startTimestamp) / 1000.0} seconds")
          context.system.scheduler.scheduleOnce(serviceLag, sender, HttpResponse(entity = (new Date()).toString))
        case HttpRequest(_, Uri.Path("/reset"), _, _, _) ⇒
          requests = 0
          startTimestamp = System.currentTimeMillis()
          sender ! HttpResponse(entity = s"OK")
        case HttpRequest(_, Uri.Path("/stats"), _, _, _) ⇒
          log.info("Serving stats")
          val elapsed = (System.currentTimeMillis() - startTimestamp) / 1000.0
          sender ! HttpResponse(entity = s"$requests, ${requests / elapsed} req/sec")

        case HttpRequest(_, path, _, _, _) ⇒ log.error("unknown {}", path)

        case ev: Http.ConnectionClosed ⇒ log.info("Connection Closed - Received " + ev)
      }
    }), "handler")
    Await.ready(IO(Http)(system).ask(Http.Bind(testService, "localhost", port))(3.seconds), timeout)
  }

  def printAsyncResponse(futureResp: Future[HttpResponse]) = futureResp onSuccess {
    case resp: HttpResponse => println(s"Response is $resp")
  }


  "A throttled client" should {
    "publish a DiscardedClientRequest event when receiving request and queue is too full" in {
      implicit val timeout: Timeout = 60.seconds
      implicit val timeoutAsDuration: Duration = timeout.duration
      try {
        val discardEventReceiver = TestProbe()(system)

        system.eventStream.subscribe(discardEventReceiver.ref, classOf[DiscardedClientRequest[HttpRequest]])
        system.eventStream.subscribe(discardEventReceiver.ref, classOf[FailedClientRequest[HttpRequest]])

        val pipeline = sendReceive(IO(SaasFunneledChannel))

        // send one request
        Await.result(pipeline(Get(s"$interface:$port/reset")).map(_.entity.asString), timeout.duration) === "OK"

        // Please not that only 1 message per second is sent irrespective of the 3 parallel request settings
        // The DiscardedClientRequest is sent for the second message
        val sentMessage = pipeline(Get(s"$interface:$port"))
        val discardedMessage = pipeline(Get(s"$interface:$port"))

        discardEventReceiver.expectMsgType[DiscardedClientRequest[HttpRequest]] must be equalTo DiscardedClientRequest(QueueThresholdReached, Get(s"$interface:$port"))

        // After the timeout is passed other messages are sent

        Thread.sleep( 1000 )

        val thisIsSentToo = pipeline(Get(s"$interface:$port")).map( _.entity.asString )

        discardEventReceiver.expectNoMsg

        Try { Await.result(thisIsSentToo, timeoutAsDuration) } isSuccess
      }
    }
  }
  step {
    println("shutting down system")
    TestKit.shutdownActorSystem(system)
  }
}
