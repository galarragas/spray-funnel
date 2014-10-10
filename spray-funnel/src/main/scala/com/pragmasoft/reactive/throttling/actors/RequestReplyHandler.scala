package com.pragmasoft.reactive.throttling.actors

import akka.actor.{ReceiveTimeout, Actor, ActorLogging, ActorRef}
import scala.concurrent.duration.Duration.Undefined
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Future}
import akka.pattern.ask
import scala.concurrent._
import akka.util.Timeout
import spray.http.{HttpRequest, StatusCodes, HttpResponse}
import scala.reflect.ManifestFactory

sealed trait ReplyHandlingStrategy
case class FAIL(message: String) extends ReplyHandlingStrategy
case object WAIT_FOR_MORE extends ReplyHandlingStrategy
case object COMPLETE extends ReplyHandlingStrategy

/**
 * Actor responsible of tracking the current Request-Reply interaction. Will determine when the request has been
 * served and notify it to the coordinator
 *
 * Will drop any message not of the given type Reply
 *
 * @param coordinator
 */
abstract class RequestReplyHandler(coordinator: ActorRef) extends Actor with ActorLogging {

  import context.dispatcher

  override def receive: Actor.Receive = idle

  def idle: Actor.Receive = {
    case clientReq@ClientRequest(request, client, transport, requestTimeout)  =>

      implicit val callTimeout: Timeout = requestTimeout

      context setReceiveTimeout requestTimeout

      log.debug("Forwarding request {} to transport {}", request, transport)
      transport ! request

      context become waitingForTransportResponse(clientReq)
  }

  def waitingForTransportResponse(clientReq: ClientRequest[Any]): Actor.Receive = {
    case ReceiveTimeout =>
      timedOut(clientReq, s"Timeout exception while waiting for response to request ${clientReq.request}.")

    case response =>
      // Most of the time should be the transport itself but it might have delegated another actor for this session
      val transportRepresentativeActor = sender

      log.debug("Got response {} from {}", response, transportRepresentativeActor)

      validateResponse(response) match {
        case FAIL(message) =>
          failResponse(response, message)

        case COMPLETE =>
          clientReq.client ! response
          log.debug("Ready")
          coordinator ! Ready
          backToIdle()

        case WAIT_FOR_MORE =>
          clientReq.client ! response
          log.debug("Received response {}, waiting for more content", response)
          context become waitingForMoreReplies(transportRepresentativeActor, clientReq)
      }
  }

  def waitingForMoreReplies(transportRepresentativeActor: ActorRef, originClientRequest: ClientRequest[Any]): Actor.Receive = {
    case ReceiveTimeout =>
      timedOut(originClientRequest, s"Timeout exception while waiting for more multi-part responses to request ${originClientRequest.request}.")

    case response =>
      val forwardTo = if(sender == transportRepresentativeActor) originClientRequest.client else transportRepresentativeActor

      log.debug("Got reply {} from {}", response, sender)

      validateFurtherResponse(response) match {
        case FAIL(message) =>
          failResponse(response, message)

        case COMPLETE =>
          originClientRequest.client ! response
          log.debug("Last reply of mulit-part response received. Notifying I'm Ready to coordinator")
          coordinator ! Ready
          backToIdle()

        case WAIT_FOR_MORE =>
          log.debug("Received reply {} and forwarding it to {}, waiting for further content", response, forwardTo)
          forwardTo ! response

      }
  }

  def failResponse(response: Any, additionalMsg: String) {
    sys.error(s"Unexpected response $response of type ${response.getClass} from transport. $additionalMsg ")
  }

  def timedOut(clientReq: ClientRequest[Any], msg: String) {
    log.warning(msg)
    requestTimedOut(clientReq)

    log.debug("Timed out, returning control to coordinator")

    coordinator ! Ready
    backToIdle()
  }

  def backToIdle() : Unit = {
    context setReceiveTimeout Undefined
    context become idle
  }


  def requestTimedOut(clientRequest: ClientRequest[Any]): Unit

  def validateResponse(response: Any): ReplyHandlingStrategy
  def validateFurtherResponse(response: Any): ReplyHandlingStrategy
}

abstract class SimpleRequestReplyHandler[Reply](coordinator: ActorRef)(implicit replyManifest: Manifest[Reply]) extends RequestReplyHandler(coordinator) {
  //Accepting replies of type ${manifest.runtimeClass}

  override def validateResponse(response: Any): ReplyHandlingStrategy = {
    if (replyManifest.runtimeClass.isAssignableFrom(response.getClass))
      COMPLETE
    else
      FAIL(s"Accepting replies of type ${manifest.runtimeClass} ")
  }

  override def validateFurtherResponse(response: Any) = validateResponse(response)
}
