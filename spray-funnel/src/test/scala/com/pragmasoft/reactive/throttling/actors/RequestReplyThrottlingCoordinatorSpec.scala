package com.pragmasoft.reactive.throttling.actors

import akka.testkit.{ImplicitSender, TestProbe, TestKit}
import akka.actor.{ActorRefFactory, Props, ActorRef, ActorSystem}
import scala.concurrent.duration._
import spray.http.HttpRequest
import spray.client.pipelining._
import com.pragmasoft.reactive.throttling.actors.handlerspool.{SetHandlerPool, RequestHandlersPool}
import org.mockito.Mockito._
import org.mockito.Matchers._
import akka.testkit.TestActor.AutoPilot
import com.pragmasoft.reactive.throttling.threshold._
import org.specs2.specification.Scope
import org.specs2.time.NoTimeConversions
import org.specs2.mutable.Specification
import org.specs2.mock._
import spray.util.Utils
import com.typesafe.config.ConfigFactory
import com.pragmasoft.reactive.throttling.http.{DiscardReason, DiscardedClientRequest}
import DiscardReason._
import com.pragmasoft.reactive.throttling.http.DiscardedClientRequest

class TestCoordinator[Request](
                                transport: ActorRef,
                                frequencyThreshold: Frequency,
                                requestTimeout: FiniteDuration,
                                val handlersPool: RequestHandlersPool,
                                requestExpiry: Duration,
                                maxQueueSize: Int
                                )(implicit manifest: Manifest[Request])
  extends RequestReplyThrottlingCoordinator[Request](transport, frequencyThreshold, requestTimeout, requestExpiry, maxQueueSize)(manifest){

  override def requestExpired(clientRequest: ClientRequest[Request]) : Unit = {
    context.system.eventStream.publish( DiscardedClientRequest(Expired, clientRequest.request) )
  }

  override def requestRefused(clientRequest: ClientRequest[Request]) : Unit = {
    context.system.eventStream.publish( DiscardedClientRequest(QueueThresholdReached, clientRequest.request) )
  }
}

class RequestReplyThrottlingCoordinatorSpec extends Specification with NoTimeConversions with Mockito {

  abstract class ActorTestScope(actorSystem: ActorSystem) extends TestKit(actorSystem) with ImplicitSender with Scope {
    val requestTimeout = 2 minutes

    val transport = TestProbe()

    def testCoordinator[Request](frequencyThreshold: Frequency, handlersPool: RequestHandlersPool,
                                 requestExpiry: Duration = Duration.Inf,
                                 maxQueueSize: Int = 0)(implicit manifest: Manifest[Request]): ActorRef =
      actorSystem.actorOf(Props(classOf[TestCoordinator[Request]], transport.ref, frequencyThreshold, requestTimeout, handlersPool, requestExpiry, maxQueueSize, manifest))

  }

  val testConf = ConfigFactory.parseString(
    """
    akka {
      loglevel = INFO
      loggers = ["akka.event.slf4j.Slf4jLogger"]
      log-dead-letters-during-shutdown=off
    }
    """)


  val system = ActorSystem(Utils.actorSystemNameFrom(getClass), testConf)

  "RequestReplyThrottlingCoordinator" should {

    "forward request to handler if available" in new ActorTestScope(system) {
      val testHandler = TestProbe()
      val coordinator = testCoordinator[HttpRequest](10 perSecond, SetHandlerPool(Set(testHandler.ref)))

      coordinator ! Get("localhost:9090")

      testHandler.expectMsg(ClientRequest(Get("localhost:9090"), testActor, transport.ref, requestTimeout))
    }

    "forward request to just one handler" in new ActorTestScope(system) {
      val testHandler1 = TestProbe()
      val testHandler2 = TestProbe()

      val handlersPool = mock[RequestHandlersPool]
      when(handlersPool.get()).thenReturn(testHandler1.ref).thenReturn(testHandler2.ref)

      val coordinator = testCoordinator[HttpRequest](10 perSecond, handlersPool)

      coordinator ! Get("localhost:9090")

      testHandler1.expectMsg(ClientRequest(Get("localhost:9090"), testActor, transport.ref, requestTimeout))
      testHandler2.expectNoMsg(2 seconds)
    }

    "use other actors in the created set until the used ones are not available again" in new ActorTestScope(system) {
      val testHandler1 = TestProbe()
      val testHandler2 = TestProbe()

      val handlersPool = mock[RequestHandlersPool]
      when(handlersPool.get()).thenReturn(testHandler1.ref).thenReturn(testHandler2.ref)

      val coordinator = testCoordinator[HttpRequest](10 perSecond, handlersPool)

      coordinator ! Get("localhost:9090")
      coordinator ! Get("localhost:9091")

      testHandler1.expectMsgType[ClientRequest[HttpRequest]] must be equalTo ClientRequest(Get("localhost:9090"), testActor, transport.ref, requestTimeout)
      testHandler2.expectMsgType[ClientRequest[HttpRequest]] must be equalTo ClientRequest(Get("localhost:9091"), testActor, transport.ref, requestTimeout)
    }

    "not serve requests until one of the available actors is available" in new ActorTestScope(system) {
      val testHandler1 = TestProbe()
      val coordinator = testCoordinator[HttpRequest](10 perSecond, SetHandlerPool(Set(testHandler1.ref)))

      coordinator ! Get("localhost:9090")
      coordinator ! Get("localhost:9091")

      testHandler1.expectMsgType[ClientRequest[HttpRequest]] must be equalTo ClientRequest(Get("localhost:9090"), testActor, transport.ref, requestTimeout)

      testHandler1.expectNoMsg(1 second)

      testHandler1.send(coordinator, Ready)

      testHandler1.expectMsgType[ClientRequest[HttpRequest]] must be equalTo ClientRequest(Get("localhost:9091"), testActor, transport.ref, requestTimeout)

    }

    "delay request if serving more than allowed per interval" in new ActorTestScope(system) {
      val testHandler = TestProbe()

      val alwaysHasHandlersAvailable = mock[RequestHandlersPool]
      when(alwaysHasHandlersAvailable.get()).thenReturn(testHandler.ref)
      when(alwaysHasHandlersAvailable.isEmpty).thenReturn(false)

      val coordinator = testCoordinator[HttpRequest](3 every (3 seconds), alwaysHasHandlersAvailable)

      coordinator ! Get("localhost:9090")
      coordinator ! Get("localhost:9091")
      coordinator ! Get("localhost:9092")
      coordinator ! Get("localhost:9093")

      testHandler.expectMsg(ClientRequest(Get("localhost:9090"), testActor, transport.ref, requestTimeout))
      testHandler.expectMsg(ClientRequest(Get("localhost:9091"), testActor, transport.ref, requestTimeout))
      testHandler.expectMsg(ClientRequest(Get("localhost:9092"), testActor, transport.ref, requestTimeout))
      testHandler.expectNoMsg(3 seconds)

      testHandler.expectMsgType[ClientRequest[HttpRequest]] must be equalTo ClientRequest(Get("localhost:9093"), testActor, transport.ref, requestTimeout)
    }

    "ignore messages of wrong type" in new ActorTestScope(system) {
      val testHandler = TestProbe()
      val coordinator = testCoordinator[String](10 perSecond, SetHandlerPool(Set(testHandler.ref)))

      coordinator ! Get("localhost:9093")
      coordinator ! "I should be handled"

      testHandler.expectMsgType[ClientRequest[String]] must be equalTo ClientRequest("I should be handled", testActor, transport.ref, requestTimeout)
    }

    "return actors to pool" in new ActorTestScope(system) {
      val testHandler = TestProbe()

      val pool = mock[RequestHandlersPool]
      when(pool.get()).thenReturn(testHandler.ref)
      when(pool.isEmpty).thenReturn(false)

      val coordinator = testCoordinator[HttpRequest](3 every (3 seconds), pool)

      testHandler.send(coordinator, Ready)

      there was one(pool).putBack( same(testHandler.ref) )
    }

    "get actors from pool" in new ActorTestScope(system) {
      val handler = TestProbe()
      val pool = mock[RequestHandlersPool]
      when(pool.get()).thenReturn(handler.ref)
      when(pool.isEmpty).thenReturn(false)

      val coordinator = testCoordinator[String](3 every (3 seconds), pool)

      coordinator ! "hello"

      //need to give time to coordinator to receive the message
      handler.expectMsgClass(classOf[ClientRequest[String]])

      there was one(pool).isEmpty
      there was one(pool).get
    }

    "discard expired requests" in new ActorTestScope(system) {
      val testHandler1 = TestProbe()
      val coordinator = testCoordinator[HttpRequest](1 perSecond, SetHandlerPool(Set(testHandler1.ref)), requestExpiry = 100 milliseconds)

      coordinator ! Get("localhost:9090") // This is sent
      coordinator ! Get("localhost:9091") // this is enqueued for 100 millis and then expires

      testHandler1.expectMsgType[ClientRequest[HttpRequest]] must be equalTo ClientRequest(Get("localhost:9090"), testActor, transport.ref, requestTimeout)

      testHandler1.expectNoMsg(1 second)

      testHandler1.send(coordinator, Ready)

      testHandler1.expectNoMsg(1 second)
    }

    "call discard expired requests handler method" in {
      val systemWithNoEventSent = ActorSystem("systemWithNoEventSent", testConf)

      try {
        new ActorTestScope(systemWithNoEventSent) {
          val discardEventReceiver = TestProbe()

          systemWithNoEventSent.eventStream.subscribe(discardEventReceiver.ref, classOf[DiscardedClientRequest[HttpRequest]])

          val testHandler1 = TestProbe()
          val coordinator = testCoordinator[HttpRequest](1 perSecond, SetHandlerPool(Set(testHandler1.ref)), requestExpiry = 100 milliseconds)

          coordinator ! Get("localhost:9090") // This is sent
          coordinator ! Get("localhost:9091/shouldExpire") // this is enqueued for 100 millis and then expires

          Thread.sleep(1000)

          // The message is discarded only when browsing for a new request to serve
          testHandler1.send(coordinator, Ready)
          testHandler1.send(coordinator, Ready)

          discardEventReceiver.expectMsgType[DiscardedClientRequest[HttpRequest]] must be equalTo DiscardedClientRequest(Expired, Get("localhost:9091/shouldExpire"))
        }
      } finally {
        systemWithNoEventSent.shutdown()
      }
    }

    "discard request received when queue is too full" in new ActorTestScope(system) {
      val testHandler1 = TestProbe()
      val coordinator = testCoordinator[HttpRequest](10 perSecond, SetHandlerPool(Set(testHandler1.ref)), maxQueueSize = 1)

      coordinator ! Get("localhost:9090") // This is sent
      coordinator ! Get("localhost:9091") // This is enqueued
      coordinator ! Get("localhost:9092") // This should be discarded

      testHandler1.expectMsgType[ClientRequest[HttpRequest]] must be equalTo ClientRequest(Get("localhost:9090"), testActor, transport.ref, requestTimeout)

      testHandler1.expectNoMsg(1 second)

      testHandler1.send(coordinator, Ready)

      testHandler1.expectMsgType[ClientRequest[HttpRequest]] must be equalTo ClientRequest(Get("localhost:9091"), testActor, transport.ref, requestTimeout)

      testHandler1.send(coordinator, Ready)

      testHandler1.expectNoMsg(1 second)
    }

    "call request discarding handler method when queue is too full" in {
      val systemWithNoEventSent = ActorSystem("systemWithNoEventSent", testConf)

      try {
        new ActorTestScope(systemWithNoEventSent) {
          val discardEventReceiver = TestProbe()

          systemWithNoEventSent.eventStream.subscribe(discardEventReceiver.ref, classOf[DiscardedClientRequest[HttpRequest]])

          val coordinator = testCoordinator[HttpRequest](10 perSecond, SetHandlerPool(Set.empty[ActorRef]), maxQueueSize = 1)

          coordinator ! Get("localhost:9091") // This is enqueued
          coordinator ! Get("localhost:9092/shouldBeDiscarded") // This should be discarded

          discardEventReceiver.expectMsgType[DiscardedClientRequest[HttpRequest]] must be equalTo DiscardedClientRequest(QueueThresholdReached, Get("localhost:9092/shouldBeDiscarded"))
        }
      } finally {
        systemWithNoEventSent.shutdown()
      }
    }

    "shut down when transport terminates" in new ActorTestScope(system)  {

      val deathListener = TestProbe()
      val coordinator = testCoordinator[HttpRequest](10 perSecond, SetHandlerPool(Set.empty[ActorRef]), maxQueueSize = 1)
      deathListener.watch(coordinator)

      system.stop(transport.ref)

      deathListener.expectTerminated(coordinator)
    }

    "shut down pool when transport terminates" in new ActorTestScope(system)  {

      val handlersPool = mock[RequestHandlersPool]
      val coordinator = testCoordinator[HttpRequest](10 perSecond, handlersPool)

      system.stop(transport.ref)

      there was one(handlersPool).shutdown()(any[ActorRefFactory])
    }

    "shut down pool when stopped" in new ActorTestScope(system)  {

      val handlersPool = mock[RequestHandlersPool]
      val coordinator = testCoordinator[HttpRequest](10 perSecond, handlersPool)

      system.stop(coordinator)

      Thread.sleep(1000)

      there was one(handlersPool).shutdown()(any[ActorRefFactory])
    }
  }

  step {
    TestKit.shutdownActorSystem(system)
  }
}
