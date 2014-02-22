package com.pragmasoft.reactive.throttling.actors.handlerspool

import com.pragmasoft.reactive.throttling.actors.handlerspool.RequestHandlersPool

trait FixedSizePool {
  self : HandlerFactory =>

  def poolSize: Int

  val handlersPool: RequestHandlersPool = SetHandlerPool(poolSize)(createHandler)
}
