package com.pragmasoft.reactive.throttling.actors.handlerspool

import akka.actor.{ActorRefFactory, ActorSystem, ActorRef, ActorLogging}

/**
 * Actor pool creating a new actor for any new request and stopping the actor when returned to the pool
 *
 */
trait OneActorPerRequestPool {
  self: HandlerFactory with ActorLogging =>

  val handlersPool : ActorPool = new ActorPool {
    override def isEmpty: Boolean = false

    override def putBack(handler: ActorRef): Unit = {
      log.debug("Actor {} completed its job, stopping it", handler.path)
      context stop handler
    }

    def shutdown()(implicit context: ActorRefFactory) : Unit = ()

    override def get(): ActorRef = createHandler()
  }

}
