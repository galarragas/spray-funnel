package com.pragmasoft.reactive.throttling.actors

import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{ActorRef, Props, ActorSystem}
import scala.concurrent.duration._
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions
import org.specs2.specification.Scope
import spray.util.Utils

class RequestReplyHandlerSpec extends Specification with NoTimeConversions {

  implicit val system = ActorSystem(Utils.actorSystemNameFrom(getClass))

  def createHandler[Reply](coordinator: ActorRef)(implicit manifest: Manifest[Reply]): ActorRef =
    system.actorOf(Props(classOf[RequestReplyHandler[Reply]], coordinator, manifest))

  abstract class ActorTestScope(actorSystem: ActorSystem) extends TestKit(actorSystem) with ImplicitSender with Scope
  
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
  }

  step {
    system.shutdown()
  }
}
