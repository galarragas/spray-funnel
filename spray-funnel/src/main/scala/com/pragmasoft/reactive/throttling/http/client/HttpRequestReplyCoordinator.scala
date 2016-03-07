package com.pragmasoft.reactive.throttling.http.client

import akka.actor._
import akka.io
import akka.util.Timeout
import com.pragmasoft.reactive.throttling.actors.{ClientRequest, _}
import com.pragmasoft.reactive.throttling.actors.handlerspool.{FixedSizePool, HandlerFactory, OneActorPerRequestPool}
import com.pragmasoft.reactive.throttling.http.DiscardReason._
import com.pragmasoft.reactive.throttling.http.{DiscardedClientRequest, FailedClientRequest, _}
import com.pragmasoft.reactive.throttling.threshold.Frequency
import spray.can.Http
import spray.http._
import spray.util._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._


object HttpClientRequestReplyHandler {
  def props(coordinator: ActorRef) = Props(classOf[HttpClientRequestReplyHandler], coordinator)
}

class HttpClientRequestReplyHandler(coordinator: ActorRef) extends RequestReplyHandler(coordinator) {
    override def requestTimedOut(clientRequest: ClientRequest[Any]): Unit =
      context.system.eventStream.publish(FailedClientRequest(FailureReason.Timeout, clientRequest.request))

  override def validateResponse(response: Any): ReplyHandlingStrategy = response match {
    case _: HttpResponse => COMPLETE
    case ChunkedResponseStart(_) => WAIT_FOR_MORE
    case f: Status.Failure => FAIL(f)
    case _ => FAIL("Something unknown happened")
  }

  override def validateFurtherResponse(response: Any): ReplyHandlingStrategy = response match {
    case _: MessageChunk => WAIT_FOR_MORE
    case _: ChunkedMessageEnd => COMPLETE
    case f: Status.Failure => FAIL(f)
    case _ => FAIL("Something unknown happened")
  }
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