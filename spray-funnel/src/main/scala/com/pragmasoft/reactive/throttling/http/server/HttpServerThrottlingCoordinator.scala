package com.pragmasoft.reactive.throttling.http.server

import akka.actor.{ActorSystem, ActorRefFactory, Props, ActorRef}
import com.pragmasoft.reactive.throttling.threshold.Frequency
import com.pragmasoft.reactive.throttling.actors._
import spray.http._
import com.pragmasoft.reactive.throttling.actors.handlerspool.{OneActorPerRequestPool, FixedSizePool, HandlerFactory}
import scala.concurrent.ExecutionContext
import akka.util.Timeout
import akka.io
import spray.can.Http
import spray.util._
import scala.concurrent.duration._
import com.pragmasoft.reactive.throttling.threshold.Frequency
import scala.reflect.ManifestFactory
import com.pragmasoft.reactive.throttling.http._
import DiscardReason._
import com.pragmasoft.reactive.throttling.http.DiscardReason
import com.pragmasoft.reactive.throttling.actors.ClientRequest
import com.pragmasoft.reactive.throttling.http.FailedClientRequest
import com.pragmasoft.reactive.throttling.threshold.Frequency
import com.pragmasoft.reactive.throttling.http.DiscardedClientRequest
import spray.http.HttpRequest
import com.pragmasoft.reactive.throttling.actors.ClientRequest
import spray.http.HttpResponse
import com.pragmasoft.reactive.throttling.threshold.Frequency
import com.pragmasoft.reactive.throttling.http.DiscardedClientRequest
import com.pragmasoft.reactive.throttling.http.HttpThrottlingConfiguration


object HttpServerRequestReplyHandler {
  def props(coordinator: ActorRef) = Props(classOf[HttpServerRequestReplyHandler], coordinator)
}

class HttpServerRequestReplyHandler(coordinator: ActorRef) extends RequestReplyHandler[HttpResponse](coordinator)(ManifestFactory.classType(classOf[HttpResponse])) {
    override def requestTimedOut(clientRequest: ClientRequest[Any]): Unit =
      clientRequest.client ! HttpResponse(StatusCodes.InternalServerError,
        "The server was not able to produce a timely response to your request.")
}


abstract class AbstractHttpServerThrottlingCoordinator(
                                                  httpServer: ActorRef,
                                                  frequencyThreshold: Frequency,
                                                  requestTimeout: FiniteDuration,
                                                  requestExpiry: Duration,
                                                  maxQueueSize: Int
  ) extends RequestReplyThrottlingCoordinator[HttpRequest] (
        httpServer, frequencyThreshold, requestTimeout, requestExpiry, maxQueueSize
  ) with HandlerFactory {

  override def createHandler() = context.actorOf(HttpServerRequestReplyHandler.props(self))

  override def requestExpired(clientRequest: ClientRequest[HttpRequest]) : Unit = {
    context.system.eventStream.publish( DiscardedClientRequest(Expired, clientRequest.request) )
  }

  override def requestRefused(request: HttpRequest) : Unit = {
    context.system.eventStream.publish( DiscardedClientRequest(QueueThresholdReached, request) )
  }

  override def postStop(): Unit = {
    super.postStop()

    log.debug("Stopping http server")

    context.stop(httpServer)
  }
}

class FixedPoolSizeHttpServerThrottlingCoordinator(
                                                  httpServer: ActorRef,
                                                  frequencyThreshold: Frequency,
                                                  requestTimeout: FiniteDuration,
                                                  val poolSize: Int,
                                                  requestExpiry: Duration,
                                                  maxQueueSize: Int
  ) extends AbstractHttpServerThrottlingCoordinator(
    httpServer, frequencyThreshold, requestTimeout, requestExpiry, maxQueueSize
  ) with FixedSizePool


class HttpServerThrottlingCoordinator(
                                 httpServer: ActorRef,
                                 frequencyThreshold: Frequency,
                                 requestTimeout: FiniteDuration,
                                 requestExpiry: Duration,
                                 maxQueueSize: Int
   ) extends AbstractHttpServerThrottlingCoordinator(
      httpServer, frequencyThreshold, requestTimeout, requestExpiry, maxQueueSize
   ) with OneActorPerRequestPool


object HttpServerThrottlingCoordinator {
  def propsForFrequencyAndParallelRequests(frequencyThreshold: Frequency, maxParallelRequests: Int, requestTimeout: Timeout, httpServer: ActorRef) =
    Props(classOf[FixedPoolSizeHttpServerThrottlingCoordinator], httpServer, frequencyThreshold, requestTimeout.duration, maxParallelRequests, Duration.Inf, 0)

  def propsForFrequency(frequencyThreshold: Frequency, requestTimeout: Timeout, httpServer: ActorRef) =
    Props(classOf[HttpServerThrottlingCoordinator], httpServer, frequencyThreshold, requestTimeout.duration, Duration.Inf, 0)

  def propsForConfig(config: HttpThrottlingConfiguration, httpServer: ActorRef) : Props = {
    if( (config.requestConfig.parallelThreshold > 0) && (config.requestConfig.parallelThreshold != Int.MaxValue) ) {
      Props(
        classOf[FixedPoolSizeHttpServerThrottlingCoordinator],
        httpServer,
        config.frequencyThreshold,
        config.requestConfig.timeout.duration,
        config.requestConfig.parallelThreshold,
        config.requestConfig.expiry,
        config.requestConfig.maxQueueSize
      )
    } else {
      Props(
        classOf[HttpServerThrottlingCoordinator],
        httpServer,
        config.frequencyThreshold,
        config.requestConfig.timeout.duration,
        config.requestConfig.expiry,
        config.requestConfig.maxQueueSize
      )
    }
  }
}