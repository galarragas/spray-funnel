package com.pragmasoft.reactive.throttling.util.stubserver

import akka.actor._
import spray.can.Http
import spray.http._
import spray.http.StatusCodes._
import akka.io.IO
import akka.pattern._
import scala.concurrent.duration._
import scala.concurrent.{Future, Await}
import spray.client.pipelining._
import akka.util.Timeout
import scala.collection.mutable.ListBuffer


import spray.http.HttpRequest
import spray.http.HttpResponse

case class DelayedResponse(response: HttpResponse, sender: ActorRef, delay: FiniteDuration)

class DelayedResponseActor extends Actor {
  override def receive: Actor.Receive = {
    case DelayedResponse(response, replyTo, delay) =>
      if (delay.toMillis > 0) {
        Thread.sleep(delay.toMillis)
      }
      replyTo ! response
  }
}

class PerConnectionActor(serviceResponseDelay: FiniteDuration) extends Actor with ActorLogging {
  val responseActor = context.actorOf(Props(classOf[DelayedResponseActor]))

  val requestedParams = ListBuffer[Int]()

  def receive = {

    case request@HttpRequest(HttpMethods.GET, Uri(_, _, path, query, _), _, _, _) if path.toString == s"/$servicePath" ⇒
      requestedParams.append(query.getOrElse("id", "0").toInt)
      responseActor ! DelayedResponse(HttpResponse(OK, HttpEntity("pong")), sender, serviceResponseDelay)

    case x: HttpRequest if x.uri.path.toString == s"/$countPath" ⇒
      sender ! HttpResponse(OK, HttpEntity(requestedParams.mkString("", ",", "")))

    case _: Http.ConnectionClosed ⇒ context.stop(self)
  }
}

class StubServer(interface: String, port: Int, serviceResponseDelay: FiniteDuration) extends Actor with ActorLogging {
  val allConnections = context.actorOf(Props(new PerConnectionActor(serviceResponseDelay)))

  override def receive: Actor.Receive = {
    case Http.Connected(peer, _) ⇒
      log.debug("Connected with {}", peer)
      sender ! Http.Register(allConnections)
  }
}


trait StubServerSupport {
  def context: ActorSystem

  var service: ActorRef = _

  var interface: String = _
  var port: Int = _

  def setup(interface: String, port: Int, responseDelay: FiniteDuration): Unit = {
    this.interface = interface
    this.port = port

    implicit val _ = context

    service = context.actorOf(Props(classOf[StubServer], interface, port, responseDelay))
    Await.ready(IO(Http).ask(Http.Bind(service, interface, port))(3.seconds), 3.seconds)
  }

  def requestList(implicit requestTimeout: Timeout): List[Int] = {
    require(service != null, "System has not been initialised. Need to run setup before interacting with stub server")

    implicit val execContext = context.dispatcher
    implicit val actorContext = context

    val countPipeline = sendReceive ~> extractRequestList

    Await.result(countPipeline {
      Get(s"http://$interface:$port/$countPath")
    }, requestTimeout.duration)
  }

  def shutdown() {
    IO(Http)(context).tell(Http.Unbind, service)
    context.stop(service)
  }

  def extractRequestList(httpResponseFuture: Future[HttpResponse]): Future[List[Int]] = {
    implicit val execContext = context.dispatcher

    httpResponseFuture map {
      response => response.entity.data.asString.split(",").map(_.toInt).toList
    }
  }
}