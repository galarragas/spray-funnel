package com.pragmasoft.reactive.throttling.actors.handlerspool

import akka.actor.{ActorRef, Actor}

trait HandlerFactory extends Actor {
  def createHandler() : ActorRef
}
