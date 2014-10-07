package com.pragmasoft.reactive.throttling.http.client

import akka.actor.{ActorSystem, ActorRefFactory, Props, ActorRef}
import com.pragmasoft.reactive.throttling.threshold.Frequency
import com.pragmasoft.reactive.throttling.actors._
import spray.http.HttpRequest
import com.pragmasoft.reactive.throttling.actors.handlerspool.{OneActorPerRequestPool, FixedSizePool, HandlerFactory}
import scala.concurrent.ExecutionContext
import akka.util.Timeout
import akka.io
import spray.can.Http
import spray.util._
import scala.concurrent.duration._
import scala.reflect.ManifestFactory
import com.pragmasoft.reactive.throttling.http._
import DiscardReason._
import spray.http.HttpRequest
import com.pragmasoft.reactive.throttling.actors.ClientRequest
import com.pragmasoft.reactive.throttling.http.FailedClientRequest
import spray.http.HttpResponse
import com.pragmasoft.reactive.throttling.threshold.Frequency
import com.pragmasoft.reactive.throttling.http.DiscardedClientRequest


object HttpClientRequestReplyHandler {
  def props(coordinator: ActorRef) = Props(classOf[HttpClientRequestReplyHandler], coordinator)
}

class HttpClientRequestReplyHandler(coordinator: ActorRef) extends SimpleRequestReplyHandler[HttpResponse](coordinator)(ManifestFactory.classType(classOf[HttpResponse])) {
    override def requestTimedOut(clientRequest: ClientRequest[Any]): Unit =
      context.system.eventStream.publish(FailedClientRequest(FailureReason.Timeout, clientRequest.request))
}


abstract class AbstractHttpClientThrottlingCoordinator(
                                                              transport: ActorRef,
                                                              frequencyThreshold: Frequency,
                                                              requestTimeout: FiniteDuration,
                                                              requestExpiry: Duration,
                                                              maxQueueSize: Int
                                                              ) extends RequestReplyThrottlingCoordinator[HttpRequest](transport, frequencyThreshold, requestTimeout, requestExpiry, maxQueueSize) with HandlerFactory {

  override def createHandler() = context.actorOf(HttpClientRequestReplyHandler.props(self))

  override def requestExpired(clientRequest: ClientRequest[HttpRequest]) : Unit = {
    context.system.eventStream.publish( DiscardedClientRequest(Expired, clientRequest.request) )
  }

  override def requestRefused(clientRequest: ClientRequest[HttpRequest]) : Unit = {
    context.system.eventStream.publish( DiscardedClientRequest(QueueThresholdReached, clientRequest.request) )
  }
}

class FixedPoolSizeHttpClientThrottlingCoordinator(
                                                          transport: ActorRef,
                                                          frequencyThreshold: Frequency,
                                                          requestTimeout: FiniteDuration,
                                                          val poolSize: Int,
                                                          requestExpiry: Duration,
                                                          maxQueueSize: Int
                                                          ) extends AbstractHttpClientThrottlingCoordinator(transport, frequencyThreshold, requestTimeout, requestExpiry, maxQueueSize) with FixedSizePool


class HttpClientThrottlingCoordinator(
                                             transport: ActorRef,
                                             frequencyThreshold: Frequency,
                                             requestTimeout: FiniteDuration,
                                             requestExpiry: Duration,
                                             maxQueueSize: Int
                                             ) extends AbstractHttpClientThrottlingCoordinator(transport, frequencyThreshold, requestTimeout, requestExpiry, maxQueueSize) with OneActorPerRequestPool


object HttpClientThrottlingCoordinator {
  def propsForFrequencyAndParallelRequestsWithTransport(frequencyThreshold: Frequency, maxParallelRequests: Int, transport: ActorRef, requestTimeout: Timeout) =
    Props(classOf[FixedPoolSizeHttpClientThrottlingCoordinator], transport, frequencyThreshold, requestTimeout.duration, maxParallelRequests, Duration.Inf, 0)

  def propsForFrequencyWithTransport(frequencyThreshold: Frequency, transport: ActorRef, requestTimeout: Timeout) =
    Props(classOf[HttpClientThrottlingCoordinator], transport, frequencyThreshold, requestTimeout.duration, Duration.Inf, 0)

  def propsForConfigAndTransport(config: HttpThrottlingConfiguration, transport: ActorRef) : Props = {
    if( (config.requestConfig.parallelThreshold > 0) && (config.requestConfig.parallelThreshold != Int.MaxValue) ) {
      Props(
        classOf[FixedPoolSizeHttpClientThrottlingCoordinator],
        transport,
        config.frequencyThreshold,
        config.requestConfig.timeout.duration,
        config.requestConfig.parallelThreshold,
        config.requestConfig.expiry,
        config.requestConfig.maxQueueSize
      )
    } else {
      Props(
        classOf[HttpClientThrottlingCoordinator],
        transport,
        config.frequencyThreshold,
        config.requestConfig.timeout.duration,
        config.requestConfig.expiry,
        config.requestConfig.maxQueueSize
      )
    }
  }

  def propsForFrequencyAndParallelRequests(frequencyThreshold: Frequency, maxParallelRequests: Int)
                                          (implicit refFactory: ActorRefFactory, executionContext: ExecutionContext,requestTimeout: Timeout = 60.seconds) =
    propsForFrequencyAndParallelRequestsWithTransport(frequencyThreshold , maxParallelRequests, io.IO(Http)(actorSystem), requestTimeout)

  def propsForFrequency(frequencyThreshold: Frequency)
                       (implicit refFactory: ActorRefFactory, executionContext: ExecutionContext, requestTimeout: Timeout = 60.seconds) =
    propsForFrequencyWithTransport(frequencyThreshold, io.IO(Http)(actorSystem), requestTimeout)

  def propsForConfig(config: HttpThrottlingConfiguration)(implicit actorSystem : ActorSystem, executionContext: ExecutionContext) : Props =
    propsForConfigAndTransport(config, io.IO(Http)(actorSystem))

}