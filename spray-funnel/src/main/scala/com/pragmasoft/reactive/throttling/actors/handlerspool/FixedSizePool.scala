package com.pragmasoft.reactive.throttling.actors.handlerspool

trait FixedSizePool {
  self : HandlerFactory =>

  def poolSize: Int

  val handlersPool: RequestHandlersPool = SetHandlerPool(poolSize)(createHandler)
}
