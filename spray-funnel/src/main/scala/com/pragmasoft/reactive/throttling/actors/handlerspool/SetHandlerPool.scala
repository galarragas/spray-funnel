package com.pragmasoft.reactive.throttling.actors.handlerspool

import akka.actor.ActorRef
import com.pragmasoft.reactive.throttling.actors.handlerspool.RequestHandlersPool

class SetHandlerPool(handlers: Set[ActorRef]) extends RequestHandlersPool{
  var availableHandlers: Set[ActorRef] = handlers

  override def putBack(handler: ActorRef): Unit = availableHandlers += handler

  override def get(): ActorRef = {
    val result = availableHandlers.head
    availableHandlers = availableHandlers.tail

    result
  }

  override def isEmpty: Boolean = availableHandlers.isEmpty
}

object SetHandlerPool {
  def apply(handlers: Set[ActorRef]) : SetHandlerPool = new SetHandlerPool(handlers)
  def apply(size: Int)(handlerFactory: () => ActorRef) : SetHandlerPool =
    new SetHandlerPool( List.fill(size) ( handlerFactory() ) toSet )
}