package com.pragmasoft.reactive.throttling.http.server

import akka.actor._
import akka.util.Timeout
import com.pragmasoft.reactive.throttling.actors.{ClientRequest, _}
import com.pragmasoft.reactive.throttling.actors.handlerspool.{FixedSizePool, HandlerFactory, OneActorPerRequestPool}
import com.pragmasoft.reactive.throttling.http.HttpThrottlingConfiguration
import com.pragmasoft.reactive.throttling.threshold.Frequency
import spray.http.{HttpRequest, HttpResponse, _}

import scala.concurrent.duration._


object HttpServerRequestReplyHandler {
  def props(coordinator: ActorRef) = Props(classOf[HttpServerRequestReplyHandler], coordinator)
}

class HttpServerRequestReplyHandler(coordinator: ActorRef) extends RequestReplyHandler(coordinator) {
    override def requestTimedOut(clientRequest: ClientRequest[Any]): Unit = {
      log.debug("Request {} timed out!!", clientRequest)
      clientRequest.client ! HttpResponse(StatusCodes.InternalServerError,
        "The server was not able to produce a timely response to your request.")
    }

  override def validateResponse(response: Any): ReplyHandlingStrategy = response match {
    case _: HttpResponse => COMPLETE
    case Confirmed(ChunkedResponseStart(_), _) => WAIT_FOR_MORE
    case f: Status.Failure => FAIL(f)
    case _ => FAIL("Accepting only HttpResponse or Start notification for ChunkedResponse")
  }

  override def validateFurtherResponse(response: Any): ReplyHandlingStrategy = response match {
    case Confirmed(ChunkedMessageEnd(_, _), _) => COMPLETE
    // The ChunkingActor is waiting for a SentOk ack and the class is private, I'm going to accept anything when chunking
//    case SentOk(_) => WAIT_FOR_MORE
    case Confirmed(MessageChunk(_, _), _)  => WAIT_FOR_MORE
    case _ => WAIT_FOR_MORE
  }
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
    log.debug("Sending failure response")
    clientRequest.client ! HttpResponse(StatusCodes.BandwidthLimitExceeded, "The server was not able to produce a timely response to your request.")
  }

  override def requestRefused(clientRequest: ClientRequest[HttpRequest]) : Unit = {
    log.debug("Sending failure response")
    clientRequest.client ! HttpResponse(StatusCodes.BandwidthLimitExceeded, "The server workload was too high to allow to service your request.")
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