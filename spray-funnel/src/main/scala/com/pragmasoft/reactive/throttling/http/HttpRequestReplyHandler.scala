package com.pragmasoft.reactive.throttling.http

import akka.actor.{ActorRefFactory, Props, ActorRef}
import com.pragmasoft.reactive.throttling.threshold.Frequency
import scala.concurrent.duration.FiniteDuration
import com.pragmasoft.reactive.throttling.actors.{RequestReplyHandler, RequestReplyThrottlingCoordinator}
import spray.http.{HttpResponse, HttpRequest}
import com.pragmasoft.reactive.throttling.actors.handlerspool.{OneActorPerRequestPool, FixedSizePool, HandlerFactory}
import scala.concurrent.ExecutionContext
import akka.util.Timeout
import akka.io
import spray.can.Http
import spray.util._
import com.pragmasoft.reactive.throttling.threshold.Frequency
import scala.concurrent.duration._

object HttpRequestReplyHandler {
  def props(coordinator: ActorRef) = Props(classOf[HttpRequestReplyHandler], coordinator)
}

class HttpRequestReplyHandler(coordinator: ActorRef) extends RequestReplyHandler[HttpResponse](coordinator: ActorRef)


abstract class AbstractHttpRequestReplyThrottlingCoordinator(
                                                              transport: ActorRef,
                                                              frequencyThreshold: Frequency,
                                                              requestTimeout: FiniteDuration
                                                              ) extends RequestReplyThrottlingCoordinator[HttpRequest](transport, frequencyThreshold, requestTimeout) with HandlerFactory {

  def createHandler() = context.actorOf(HttpRequestReplyHandler.props(self))
}

class FixedPoolSizeHttpRequestReplyThrottlingCoordinator(
                                                          transport: ActorRef,
                                                          frequencyThreshold: Frequency,
                                                          requestTimeout: FiniteDuration,
                                                          val poolSize: Int
                                                          ) extends AbstractHttpRequestReplyThrottlingCoordinator(transport, frequencyThreshold, requestTimeout) with FixedSizePool


class HttpRequestReplyThrottlingCoordinator(
                                             transport: ActorRef,
                                             frequencyThreshold: Frequency,
                                             requestTimeout: FiniteDuration
                                             ) extends AbstractHttpRequestReplyThrottlingCoordinator(transport, frequencyThreshold, requestTimeout) with OneActorPerRequestPool


object HttpRequestReplyCoordinator {
  def propsForFrequencyAndParallelRequestsWithTransport(frequencyThreshold: Frequency, maxParallelRequests: Int, transport: ActorRef, requestTimeout: Timeout) =
    Props(classOf[FixedPoolSizeHttpRequestReplyThrottlingCoordinator], transport, frequencyThreshold, requestTimeout.duration, maxParallelRequests)

  def propsForFrequencyWithTransport(frequencyThreshold: Frequency, transport: ActorRef, requestTimeout: Timeout) =
    Props(classOf[FixedPoolSizeHttpRequestReplyThrottlingCoordinator], transport, frequencyThreshold, requestTimeout.duration)

  def propsForFrequencyAndParallelRequests(frequencyThreshold: Frequency, maxParallelRequests: Int)
                                          (implicit refFactory: ActorRefFactory, executionContext: ExecutionContext,requestTimeout: Timeout = 60.seconds) =
    Props(classOf[FixedPoolSizeHttpRequestReplyThrottlingCoordinator], io.IO(Http)(actorSystem), frequencyThreshold, requestTimeout.duration, maxParallelRequests)

  def propsForFrequency(frequencyThreshold: Frequency)
                       (implicit refFactory: ActorRefFactory, executionContext: ExecutionContext, requestTimeout: Timeout = 60.seconds) =
    Props(classOf[FixedPoolSizeHttpRequestReplyThrottlingCoordinator], io.IO(Http)(actorSystem), frequencyThreshold, requestTimeout.duration)
}