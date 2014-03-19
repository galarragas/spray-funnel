package com.pragmasoft.reactive.throttling.actors.handlerspool

import akka.actor.{ActorRefFactory, ActorSystem, ActorRef}

/**
 * Fixed size pool actor of actors backed-up by the given actorSet
 * Will track the active ones and stop all actors at shutdown
 * 
 * @param actorSet
 */
class SetActorPool(actorSet: Set[ActorRef]) extends ActorPool {
  var availableHandlers: Set[ActorRef] = actorSet

  override def putBack(handler: ActorRef): Unit = availableHandlers += handler

  override def get(): ActorRef = {
    val result = availableHandlers.head
    availableHandlers = availableHandlers.tail

    result
  }

  override def shutdown()(implicit context: ActorRefFactory) : Unit = {
    actorSet foreach { context.stop _ }
  }

  override def isEmpty: Boolean = availableHandlers.isEmpty
}

object SetActorPool {
  /**
   * Creates a SetActorPool based on the given set
   * @param handlers
   * @return
   */
  def apply(handlers: Set[ActorRef]) : SetActorPool = new SetActorPool(handlers)

  /**
   * Creates a SetActorPool of the given size, creating the necessary number of actors with the supplied actorFactory method
   *
   * @param size          final size of the pool
   * @param actorFactory  actor creation method
   * @return
   */
  def apply(size: Int)(actorFactory: () => ActorRef) : SetActorPool =
    new SetActorPool( List.fill(size) ( actorFactory() ) toSet )
}