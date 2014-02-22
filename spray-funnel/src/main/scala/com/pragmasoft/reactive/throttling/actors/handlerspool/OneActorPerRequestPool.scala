package com.pragmasoft.reactive.throttling.actors.handlerspool

import akka.actor.{ActorRef, ActorLogging}
import com.pragmasoft.reactive.throttling.actors.handlerspool.RequestHandlersPool

trait OneActorPerRequestPool {
  self: HandlerFactory with ActorLogging =>

  val handlersPool : RequestHandlersPool = new RequestHandlersPool {
    override def isEmpty: Boolean = false

    override def putBack(handler: ActorRef): Unit = {
      log.debug("Actor {} completed its job, stopping it", handler.path)
      context stop handler
    }

    override def get(): ActorRef = createHandler()
  }

}
