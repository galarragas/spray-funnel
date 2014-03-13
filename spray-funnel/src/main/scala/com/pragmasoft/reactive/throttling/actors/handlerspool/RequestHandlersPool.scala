package com.pragmasoft.reactive.throttling.actors.handlerspool

import akka.actor.{ActorRefFactory, ActorContext, ActorSystem, ActorRef}

trait RequestHandlersPool {
  def isEmpty : Boolean
  def get() : ActorRef
  def putBack(handler: ActorRef) : Unit
  def shutdown()(implicit context: ActorRefFactory) : Unit
}
