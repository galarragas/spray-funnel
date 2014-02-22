package com.pragmasoft.reactive.throttling.actors

import akka.testkit.{ImplicitSender, TestProbe, TestKit}
import akka.actor.{Props, ActorRef, ActorSystem}
import org.scalatest.{BeforeAndAfterAll, Matchers, FlatSpecLike}
import scala.concurrent.duration._
import spray.http.{HttpResponse, HttpRequest}
import spray.client.pipelining._
import com.pragmasoft.reactive.throttling.actors.handlerspool.{RequestHandlersPool, SetHandlerPool}
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import org.mockito.Matchers.{same => sameArg}
import akka.testkit.TestActor.{KeepRunning, AutoPilot}
import com.pragmasoft.reactive.throttling.threshold._


class TestCoordinator[Request](
  transport: ActorRef,
  frequencyThreshold: Frequency,
  requestTimeout: FiniteDuration,
  val handlersPool: RequestHandlersPool
) (implicit manifest: Manifest[Request])  extends RequestReplyThrottlingCoordinator[Request](transport, frequencyThreshold, requestTimeout)(manifest)

class RequestReplyThrottlingCoordinatorSpec extends TestKit(ActorSystem("RequestReplyThrottlingCoordinatorSpec")) with FlatSpecLike
  with Matchers with BeforeAndAfterAll with ImplicitSender with MockitoSugar {

  val requestTimeout = 2 minutes

  val transport = TestProbe()

  def testCoordinator[Request](frequencyThreshold: Frequency, handlersPool: RequestHandlersPool)(implicit manifest: Manifest[Request]) : ActorRef =
    system.actorOf(Props(classOf[TestCoordinator[Request]], transport.ref, frequencyThreshold, requestTimeout, handlersPool, manifest))


  behavior of "RequestReplyThrottlingCoordinator"

  it should "forward request to handler if available" in {
    val testHandler = TestProbe()
    val coordinator =  testCoordinator[HttpRequest](10 perSecond, SetHandlerPool(Set(testHandler.ref)))

    coordinator ! Get("localhost:9090")

    testHandler.expectMsg(ClientRequest(Get("localhost:9090"), testActor, transport.ref, requestTimeout))
  }

  it should "forward request to just one handler" in {
    val testHandler1 = TestProbe()
    val testHandler2 = TestProbe()

    val handlersPool = mock[RequestHandlersPool]
    when(handlersPool.get()).thenReturn(testHandler1.ref).thenReturn(testHandler2.ref)

    val coordinator =  testCoordinator[HttpRequest](10 perSecond, handlersPool)

    coordinator ! Get("localhost:9090")

    testHandler1.expectMsg(ClientRequest(Get("localhost:9090"), testActor, transport.ref, requestTimeout))
    testHandler2.expectNoMsg(2 seconds)
  }

  it should "use other actors in the created set until the used ones are not available again" in {
    val testHandler1 = TestProbe()
    val testHandler2 = TestProbe()

    val handlersPool = mock[RequestHandlersPool]
    when(handlersPool.get()).thenReturn(testHandler1.ref).thenReturn(testHandler2.ref)

    val coordinator =  testCoordinator[HttpRequest](10 perSecond, handlersPool)

    coordinator ! Get("localhost:9090")
    coordinator ! Get("localhost:9091")

    testHandler1.expectMsg(ClientRequest(Get("localhost:9090"), testActor, transport.ref, requestTimeout))
    testHandler2.expectMsg(ClientRequest(Get("localhost:9091"), testActor, transport.ref, requestTimeout))
  }

  it should "not serve requests until one of the available actors is available" in {
    val testHandler1 = TestProbe()
    val coordinator =  testCoordinator[HttpRequest](10 perSecond, SetHandlerPool(Set(testHandler1.ref)))

    coordinator ! Get("localhost:9090")
    coordinator ! Get("localhost:9091")

    testHandler1.expectMsg(ClientRequest(Get("localhost:9090"), testActor, transport.ref, requestTimeout))

    testHandler1.expectNoMsg( 1 second )

    testHandler1.send(coordinator, Ready)

    testHandler1.expectMsg(ClientRequest(Get("localhost:9091"), testActor, transport.ref, requestTimeout))

  }

  it should "delay request if serving more than allowed per interval" in {
    val testHandler = TestProbe()

    val alwaysHasHandlersAvailable = mock[RequestHandlersPool]
    when(alwaysHasHandlersAvailable.get()).thenReturn(testHandler.ref)
    when(alwaysHasHandlersAvailable.isEmpty).thenReturn(false)


    val coordinator =  testCoordinator[HttpRequest]( 3 every (3 seconds), alwaysHasHandlersAvailable)

    coordinator ! Get("localhost:9090")
    coordinator ! Get("localhost:9091")
    coordinator ! Get("localhost:9092")
    coordinator ! Get("localhost:9093")

    testHandler.expectMsg(ClientRequest(Get("localhost:9090"), testActor, transport.ref, requestTimeout))
    testHandler.expectMsg(ClientRequest(Get("localhost:9091"), testActor, transport.ref, requestTimeout))
    testHandler.expectMsg(ClientRequest(Get("localhost:9092"), testActor, transport.ref, requestTimeout))
    testHandler.expectNoMsg(3 seconds)

    testHandler.expectMsg(ClientRequest(Get("localhost:9093"), testActor, transport.ref, requestTimeout))
  }

  it should "ignore messages of wrong type" in {
    val testHandler = TestProbe()
    val coordinator =  testCoordinator[String](10 perSecond, SetHandlerPool(Set(testHandler.ref)))

    coordinator ! Get("localhost:9093")
    coordinator ! "I should be handled"

    testHandler.expectMsg(ClientRequest("I should be handled", testActor, transport.ref, requestTimeout))
  }

  it should "return actors to pool" in {
    val testHandler = TestProbe()

    val pool = mock[RequestHandlersPool]
    when(pool.get()).thenReturn(testHandler.ref)
    when(pool.isEmpty).thenReturn(false)

    val coordinator =  testCoordinator[HttpRequest]( 3 every (3 seconds), pool)

    testHandler.send(coordinator, Ready)

    verify(pool) putBack sameArg(testHandler.ref)
  }

  it should "get actors from pool" in {
    val handler = TestProbe()
    val pool = mock[RequestHandlersPool]
    when(pool.get()).thenReturn(handler.ref)
    when(pool.isEmpty).thenReturn(false)

    val coordinator =  testCoordinator[String]( 3 every (3 seconds), pool)

    coordinator ! "hello"

    //need to give time to coordinator to receive the message
    handler.expectMsgClass(classOf[ClientRequest[String]])

    verify(pool).isEmpty
    verify(pool).get
  }
  
  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }
}
