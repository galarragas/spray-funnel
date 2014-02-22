package com.pragmasoft.reactive.throttling.actors

import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{ActorRef, Props, ActorSystem}
import org.scalatest.{BeforeAndAfterAll, Matchers, FlatSpecLike}
import scala.concurrent.duration._

class RequestReplyHandlerSpec extends TestKit(ActorSystem("RequestReplyHandlerSpec")) with FlatSpecLike
    with Matchers with BeforeAndAfterAll with ImplicitSender {

  def createHandler[Reply](coordinator: ActorRef)(implicit manifest: Manifest[Reply]) : ActorRef =
    system.actorOf(Props(classOf[RequestReplyHandler[Reply]], coordinator, manifest))


  behavior of "RequestReplyHandler"

  it should "forward request to transport" in {
    val transport = TestProbe()
    val coordinator = TestProbe()
    val handler = createHandler[String](coordinator.ref)

    handler ! ClientRequest("request", testActor, transport.ref, 1 second)

    transport.expectMsg("request")
  }

  it should "forward reply to client" in {
    val transport = TestProbe()
    val coordinator = TestProbe()
    val client = TestProbe()
    val handler = createHandler[String](coordinator.ref)

    handler ! ClientRequest("request", client.ref, transport.ref, 1 second)

    transport.expectMsg("request")
    transport.reply("Reply")

    client.expectMsg("Reply")
  }

  it should "ignore replies of wrong type" in {
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

  it should "not fail for replies of wrong type" in {
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

  it should "notify it is ready after receiving whatever response" in {
    val transport = TestProbe()
    val coordinator = TestProbe()
    val client = TestProbe()
    val handler = createHandler[String](coordinator.ref)

    handler ! ClientRequest("request", client.ref, transport.ref, 2 second)

    coordinator.expectNoMsg( 1 second )

    transport.send(handler, "Reply")

    coordinator
  }

  it should "wait the response for the timeout specified in the ClientRequest" in {
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

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }
}
