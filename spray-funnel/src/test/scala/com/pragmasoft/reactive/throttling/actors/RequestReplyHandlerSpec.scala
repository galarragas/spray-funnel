package com.pragmasoft.reactive.throttling.actors

import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{ActorRef, Props, ActorSystem}
import scala.concurrent.duration._
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions
import org.specs2.specification.Scope
import spray.util.Utils
import spray.http.HttpResponse
import com.typesafe.config.ConfigFactory
import com.pragmasoft.reactive.throttling.util.RetryExamples

class PublishTimeoutFailureReplyHandler[Reply](coordinator: ActorRef)
      (implicit replyManifest: Manifest[Reply]) extends RequestReplyHandler[Reply](coordinator)(replyManifest){
  override def requestTimedOut(clientRequest: ClientRequest[Any]): Unit =
    context.system.eventStream.publish(clientRequest.request.asInstanceOf[AnyRef])
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
    def createHandler[Reply](coordinator: ActorRef)(implicit replyManifest: Manifest[Reply]): ActorRef =
      actorSystem.actorOf(Props(classOf[PublishTimeoutFailureReplyHandler[Reply]], coordinator, replyManifest))
  }
  
  "RequestReplyHandler" should {
    "forward request to transport" in new ActorTestScope(system) {
      val transport = TestProbe()
      val coordinator = TestProbe()
      val handler = createHandler[String](coordinator.ref)

      handler ! ClientRequest("request", testActor, transport.ref, 1 second)

      transport.expectMsgType[String] must be equalTo "request"
    }

    "forward reply to client" in new ActorTestScope(system) {
      val transport = TestProbe()
      val coordinator = TestProbe()
      val client = TestProbe()
      val handler = createHandler[String](coordinator.ref)

      handler ! ClientRequest("request", client.ref, transport.ref, 1 second)

      transport.expectMsg("request")
      transport.reply("Reply")

      client.expectMsgType[String] must be equalTo "Reply"
    }

    "ignore replies of wrong type" in new ActorTestScope(system) {
      val transport = TestProbe()
      val coordinator = TestProbe()
      val client = TestProbe()
      val handler = createHandler[String](coordinator.ref)

      val handlerDeathWatch = TestProbe()

      handlerDeathWatch watch handler

      handler ! ClientRequest("request", client.ref, transport.ref, 2 second)

      transport.expectMsg("request")
      transport.reply(handler, 100)

      client.expectNoMsg()
    }

    "not fail for replies of wrong type" in new ActorTestScope(system) {
      val transport = TestProbe()
      val coordinator = TestProbe()
      val client = TestProbe()
      val handler = createHandler[String](coordinator.ref)

      val handlerDeathWatch = TestProbe()

      handlerDeathWatch watch handler

      handler ! ClientRequest("request", client.ref, transport.ref, 2 second)

      transport.expectMsg("request")
      transport.reply(handler, 100)

      client.expectNoMsg()
      handlerDeathWatch.expectNoMsg()
    }

    "notify it is ready after receiving whatever response" in new ActorTestScope(system) {
      val transport = TestProbe()
      val coordinator = TestProbe()
      val client = TestProbe()
      val handler = createHandler[String](coordinator.ref)

      handler ! ClientRequest("request", client.ref, transport.ref, 2 second)

      coordinator.expectNoMsg(1 second)

      transport.send(handler, "Reply")

      coordinator.expectMsgType[Ready.type] should not be null
    }

    "wait the response for the timeout specified in the ClientRequest" in new ActorTestScope(system) {
      val transport = TestProbe()
      val coordinator = TestProbe()
      val client = TestProbe()
      val handler = createHandler[String](coordinator.ref)

      handler ! ClientRequest("request", client.ref, transport.ref, 1 second)

      // letting the timeout expiry
      Thread.sleep(1100)

      transport.expectMsg("request")
      transport.reply(handler, "should be ignored")

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
          val handler = createHandler[String](coordinator.ref)

          handler ! ClientRequest("request", client.ref, transport.ref, 1 second)

          // letting the timeout expiry
          Thread.sleep(1100)

          eventListener.expectMsgType[String] must be equalTo "request"
        }
      } finally {
        systemWithNoEvents.shutdown()
      }
    }
  }

  step {
    system.shutdown()
  }
}
