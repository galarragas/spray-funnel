package com.pragmasoft.reactive.throttling.actors.handlerspool

import akka.actor.ActorRef

trait RequestHandlersPool {
  def isEmpty : Boolean
  def get() : ActorRef
  def putBack(handler: ActorRef) : Unit
}
