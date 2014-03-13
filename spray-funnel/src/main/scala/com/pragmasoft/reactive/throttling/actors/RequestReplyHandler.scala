package com.pragmasoft.reactive.throttling.actors

import akka.actor.{Actor, ActorLogging, ActorRef}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Future}
import akka.pattern.ask
import scala.concurrent._
import akka.util.Timeout
import spray.http.{HttpRequest, StatusCodes, HttpResponse}
import scala.reflect.ManifestFactory


abstract class RequestReplyHandler[Reply](coordinator: ActorRef)(implicit replyManifest: Manifest[Reply]) extends Actor with ActorLogging {

  import context.dispatcher

  override def receive: Actor.Receive = {
    case clientReq@ClientRequest(request, client, transport, requestTimeout)  =>

      implicit val callTimeout: Timeout = requestTimeout

      log.debug("Forwarding request {} to transport", request)

      val responseFuture: Future[Reply] = transport ? request map {
        case x if (replyManifest.runtimeClass.isAssignableFrom(x.getClass)) ⇒
          x.asInstanceOf[Reply]
        case x ⇒ sys.error(s"Unexpected response $x of type ${x.getClass} from transport. Accepting replies of type ${manifest.runtimeClass} ")
      }

      try {
        client ! Await.result(responseFuture, requestTimeout)
      } catch {
        case timeout: TimeoutException =>
          log.warning(s"Timeout exception while serving request $request. Exception: $timeout")
          requestTimedOut(clientReq)
      }

      log.debug("Ready")

      coordinator ! Ready
  }

  def requestTimedOut(clientRequest: ClientRequest[Any]): Unit
}


class HttpServerRequestReplyHandler(coordinator: ActorRef) extends RequestReplyHandler[HttpResponse](coordinator)  {
  override def requestTimedOut(clientRequest: ClientRequest[Any]): Unit = {
    // Verify right code to use
    clientRequest.client ! HttpResponse(StatusCodes.RequestTimeout)
  }
}