package com.pragmasoft.reactive.throttling.actors

import akka.actor.ActorSystem
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import org.specs2.specification.Scope
import spray.util.Utils
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions
import com.pragmasoft.reactive.throttling.actors.handlerspool.SetHandlerPool

class SetHandlerPoolSpec extends Specification with NoTimeConversions {

  abstract class ActorTestScope(actorSystem: ActorSystem) extends TestKit(actorSystem) with ImplicitSender with Scope

  implicit val system = ActorSystem(Utils.actorSystemNameFrom(getClass))

  "SetHandlersPool" should {

    "return the content of the underlying set" in new ActorTestScope(system) {

      val handlers = Set(TestProbe().ref, TestProbe().ref, TestProbe().ref)

      val pool = SetHandlerPool(handlers)

      val retrievedHandlers = Set(pool.get(), pool.get(), pool.get())

      handlers should be equalTo retrievedHandlers
    }

    "return the content of the underlying set when building with size and factory method" in new ActorTestScope(system) {

      val pool = SetHandlerPool(3) {
        () => TestProbe().ref
      }

      val retrievedHandlers = Set(pool.get(), pool.get(), pool.get())

      retrievedHandlers should have size 3
    }


    "not be empty when having content" in new ActorTestScope(system) {
      SetHandlerPool(Set(TestProbe().ref)).isEmpty should beFalse
    }

    "become empty when retrieving all content" in {
      val pool = SetHandlerPool(Set(TestProbe().ref))

      pool.get()

      pool.isEmpty should beTrue
    }

    "shut down all pool actors when asked to" in new ActorTestScope(system) {
      val poolActorDeathWatch = TestProbe()
      val poolActor = TestProbe()
      poolActorDeathWatch.watch(poolActor.ref)
      val pool = SetHandlerPool(Set(poolActor.ref))

      pool.shutdown()

      poolActorDeathWatch.expectTerminated(poolActor.ref)
    }
  }

}
