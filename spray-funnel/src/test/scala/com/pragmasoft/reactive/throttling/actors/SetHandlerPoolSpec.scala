package com.pragmasoft.reactive.throttling.actors

import org.scalatest.{FlatSpecLike, Matchers, FlatSpec}
import com.pragmasoft.reactive.throttling.actors.handlerspool.SetHandlerPool
import org.scalatest.mock.MockitoSugar
import akka.actor.{ActorSystem, ActorRef}
import akka.testkit.{TestProbe, TestKit}

class SetHandlerPoolSpec extends TestKit(ActorSystem("RequestReplyHandlerSpec")) with FlatSpecLike with  Matchers {
  
  behavior of "SetHandlersPool"
  
  it should "return the content of the underlying set" in {

    val handlers = Set( TestProbe().ref, TestProbe().ref, TestProbe().ref )

    val pool = SetHandlerPool(handlers)

    val retrievedHandlers = Set( pool.get(), pool.get(), pool.get() )

    handlers should equal(retrievedHandlers)
  }

  it should "return the content of the underlying set when building with size and factory method" in {

    val pool = SetHandlerPool(3) { () => TestProbe().ref }

    val retrievedHandlers = Set( pool.get(), pool.get(), pool.get() )

    retrievedHandlers should have size(3)
  }


  it should "not be empty when having content" in {
    SetHandlerPool(Set( TestProbe().ref )).isEmpty should be (false)
  }

  it should "become empty when retrieving all content" in {
    val pool = SetHandlerPool(Set( TestProbe().ref ))

    pool.get()

    pool.isEmpty should be (true)
  }


}
