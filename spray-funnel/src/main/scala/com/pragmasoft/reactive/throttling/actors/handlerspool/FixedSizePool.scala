package com.pragmasoft.reactive.throttling.actors.handlerspool

trait FixedSizePool {
  self : HandlerFactory =>

  def poolSize: Int

  val handlersPool: ActorPool = SetActorPool(poolSize)(createHandler)
}
