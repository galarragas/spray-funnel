package com.pragmasoft.reactive.throttling.actors

import akka.actor.{Props, ActorRef, ActorSystem}
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import com.pragmasoft.reactive.throttling.util.RetryExamples
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import org.specs2.time.NoTimeConversions
import spray.util.Utils
import scala.concurrent.duration._

case class Chunk(content: String) 
case class LastChunk(content: String)

class PublishTimeoutFailureReplyHandler(coordinator: ActorRef) extends RequestReplyHandler(coordinator) {
  override def requestTimedOut(clientRequest: ClientRequest[Any]): Unit =
    context.system.eventStream.publish(clientRequest.request.asInstanceOf[AnyRef])

  override def validateResponse(response: Any): ReplyHandlingStrategy = response match {
    case accepted: String => COMPLETE
    case Chunk(_) => WAIT_FOR_MORE
    case fail => FAIL("Accepting just strings or chunks")
  }

  override def validateFurtherResponse(response: Any): ReplyHandlingStrategy = response match {
    case Chunk(_) => WAIT_FOR_MORE
    case LastChunk(_) => COMPLETE
    case _ => FAIL("Accepting just chunks or last-chunks")
  }
}

class RequestReplyHandlerSpec extends Specification with NoTimeConversions with RetryExamples {

  val testConf = ConfigFactory.parseString(
    """
    akka {
      loglevel = INFO
      loggers = ["akka.event.slf4j.Slf4jLogger"]
      log-dead-letters-during-shutdown=off
    }
    """)


  val system = ActorSystem(Utils.actorSystemNameFrom(getClass), testConf)

  abstract class ActorTestScope(actorSystem: ActorSystem) extends TestKit(actorSystem) with ImplicitSender with Scope {
    def createHandler(coordinator: ActorRef): ActorRef =
      actorSystem.actorOf(Props(new PublishTimeoutFailureReplyHandler(coordinator)))
  }

  "RequestReplyHandler" should {
    "forward request to transport" in new ActorTestScope(system) {
      val transport = TestProbe()
      val coordinator = TestProbe()
      val handler = createHandler(coordinator.ref)

      handler ! ClientRequest("request", testActor, transport.ref, 1 second)

      transport expectMsg "request"
    }

    "forward reply to client" in new ActorTestScope(system) {
      val transport = TestProbe()
      val coordinator = TestProbe()
      val client = TestProbe()
      val handler = createHandler(coordinator.ref)

      handler ! ClientRequest("request", client.ref, transport.ref, 1 second)

      transport expectMsg "request"
      transport reply "Reply"

      client expectMsg "Reply"
    }

    "drop replies of type Int" in new ActorTestScope(system) {
      val transport = TestProbe()
      val coordinator = TestProbe()
      val client = TestProbe()
      val handler = createHandler(coordinator.ref)

      handler ! ClientRequest("request", client.ref, transport.ref, 2 second)

      transport expectMsg "request"
      transport reply 100

      client.expectNoMsg()
    }

    "not fail for replies of type Int" in new ActorTestScope(system) {
      val transport = TestProbe()
      val coordinator = TestProbe()
      val client = TestProbe()
      val handler = createHandler(coordinator.ref)

      val handlerDeathWatch = TestProbe()

      handlerDeathWatch watch handler

      handler ! ClientRequest("request", client.ref, transport.ref, 2 second)

      transport expectMsg "request"
      transport reply 100

      client.expectNoMsg()
      handlerDeathWatch.expectNoMsg()
    }

    "notify it is ready after receiving full response" in new ActorTestScope(system) {
      val transport = TestProbe()
      val coordinator = TestProbe()
      val client = TestProbe()
      val handler = createHandler(coordinator.ref)

      handler ! ClientRequest("request", client.ref, transport.ref, 2 second)

      coordinator.expectNoMsg(1 second)

      transport.send(handler, "Reply")

      coordinator expectMsg Ready
    }

    "wait the response for the timeout specified in the ClientRequest" in new ActorTestScope(system) {
      val transport = TestProbe()
      val coordinator = TestProbe()
      val client = TestProbe()
      val handler = createHandler(coordinator.ref)

      handler ! ClientRequest("request", client.ref, transport.ref, 1 second)

      // letting the timeout expiry
      Thread.sleep(1100)

      transport expectMsg "request"
      transport reply "should be ignored"

      client.expectNoMsg()
    }

    "invoke request time out handler" in {
      val systemWithNoEvents = ActorSystem("invokeRequestTimeOutHandler")

      try {
        new ActorTestScope(systemWithNoEvents) {
          val eventListener = TestProbe()

          systemWithNoEvents.eventStream.subscribe(eventListener.ref, classOf[String])

          val transport = TestProbe()
          val coordinator = TestProbe()
          val client = TestProbe()
          val handler = createHandler(coordinator.ref)

          handler ! ClientRequest("request", client.ref, transport.ref, 1 second)

          // letting the timeout expiry
          Thread.sleep(1100)

          eventListener expectMsg "request"
        }
      } finally {
        systemWithNoEvents.shutdown()
      }
    }

    "propagate response and wait for more if chunk type response" in new ActorTestScope(system) {
      val transport = TestProbe()
      val coordinator = TestProbe()
      val client = TestProbe()
      val handler = createHandler(coordinator.ref)

      handler ! ClientRequest("request", client.ref, transport.ref, 2 second)

      transport expectMsg "request"
      transport reply Chunk("chunk")

      client expectMsg Chunk("chunk")

      // Not expecting any message before the timeout occurs
      coordinator.expectNoMsg(1 second)
    }

    "propagate chunks and notify termination when closing message arrives" in new ActorTestScope(system) {
      val transport = TestProbe()
      val coordinator = TestProbe()
      val client = TestProbe()
      val handler = createHandler(coordinator.ref)

      handler ! ClientRequest("request", client.ref, transport.ref, 2 second)
      transport expectMsg "request"

      transport reply Chunk("chunk")
      client expectMsg Chunk("chunk")
      coordinator.expectNoMsg(1 second)

      transport.send(handler, Chunk("chunk"))
      client expectMsg Chunk("chunk")
      coordinator.expectNoMsg(1 second)

      transport.send(handler, Chunk("chunk"))
      client expectMsg Chunk("chunk")
      coordinator.expectNoMsg(1 second)

      transport.send(handler, LastChunk("last chunk"))
      client expectMsg LastChunk("last chunk")
      coordinator expectMsg Ready
    }
    

  }

  step {
    system.shutdown()
  }

}
