package com.pragmasoft.reactive.throttling.actors

import akka.actor.{Actor, ActorLogging, ActorRef}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Future}
import akka.pattern.ask
import scala.concurrent._
import akka.util.Timeout


class RequestReplyHandler[Reply](coordinator: ActorRef)(implicit manifest: Manifest[Reply]) extends Actor with ActorLogging {

  import context.dispatcher

  override def receive: Actor.Receive = {
    case ClientRequest(request, client, transport, requestTimeout) =>

      implicit val callTimeout: Timeout = requestTimeout

      log.debug("Forwarding request {} to transport", request)

      val responseFuture: Future[Reply] = transport ? request map {
        case x if (manifest.runtimeClass.isAssignableFrom(x.getClass)) ⇒
          x.asInstanceOf[Reply]
        case x ⇒ sys.error(s"Unexpected response $x of type ${x.getClass} from transport. Accepting replies of type ${manifest.runtimeClass} ")
      }

      try {
        client ! Await.result(responseFuture, requestTimeout)
      } catch {
        case timeout: TimeoutException =>
          log.debug(s"Timeout exception while serving request $request. Exception: $timeout")
      }

      log.debug("Ready")

      coordinator ! Ready
  }
}
