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

import spray.http.HttpMethods._
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.client.UnsuccessfulResponseException

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

class ConfigurableDelayHttpServerActor(serviceResponseDelay: FiniteDuration) extends Actor with ActorLogging {
  val requestedParams = ListBuffer[Int]()

  def receive = {

    case request@HttpRequest(GET, Uri(_, _, Uri.Path(path), query, _), _, _, _) if path == servicePath ⇒
      requestedParams.append(query.getOrElse("id", "0").toInt)
      val responseActor = context.actorOf(Props(classOf[DelayedResponseActor]))
      responseActor ! DelayedResponse(HttpResponse(OK, HttpEntity("pong")), sender, serviceResponseDelay)

    case request@HttpRequest(GET, Uri.Path(path), _, _, _) if path == countPath ⇒
      sender ! HttpResponse(OK, HttpEntity(requestedParams.mkString("", ",", "")))

    case _: Http.ConnectionClosed ⇒ context.stop(self)
  }
}

class StubServer(serviceResponseDelay: FiniteDuration, allConnectionsHandler: ActorRef) extends Actor with ActorLogging {
  override def receive: Actor.Receive = {
    case Http.Connected(peer, _) ⇒
      log.debug("Connected with {}", peer)
      sender ! Http.Register(allConnectionsHandler)
  }
}


trait StubServerSupport {
  def context: ActorSystem

  var server: ActorRef = _

  var interface: String = _
  var port: Int = _

  var allConnectionsHandler : ActorRef = _
  var serviceActor : ActorRef = _

  def setupForClientTesting(interface: String, port: Int, responseDelay: FiniteDuration): Unit = {
    this.interface = interface
    this.port = port

    implicit val _ = context

    allConnectionsHandler = context.actorOf(Props(new ConfigurableDelayHttpServerActor(responseDelay)))
    serviceActor = allConnectionsHandler
    
    server = context.actorOf(Props(classOf[StubServer], responseDelay, allConnectionsHandler))
    Await.ready(IO(Http).ask(Http.Bind(server, interface, port))(3.seconds), 3.seconds)
  }
  
  def setupForServerTesting(interface: String, port: Int, responseDelay: FiniteDuration, throttlingWrapperFactory: (ActorRef, ActorSystem) => ActorRef ): Unit = {
    this.interface = interface
    this.port = port

    implicit val _ = context

    serviceActor = context.actorOf(Props(new ConfigurableDelayHttpServerActor(responseDelay)))
    allConnectionsHandler = throttlingWrapperFactory( serviceActor, context )

    server = context.actorOf(Props(classOf[StubServer], responseDelay, allConnectionsHandler))
    Await.ready(IO(Http).ask(Http.Bind(server, interface, port))(3.seconds), 3.seconds)
  }

  def requestList(implicit requestTimeout: Timeout): List[Int] = {
    require(server != null, "System has not been initialised. Need to run setup before interacting with stub server")

    implicit val execContext = context.dispatcher
    implicit val actorContext = context

    // Sending requests directly to the all connections handler actor, this will avoid any throttling IF configured
    val countPipeline = sendReceive(serviceActor) ~> extractRequestList

    Await.result(countPipeline { Get(s"http://$interface:$port$countPath") }, requestTimeout.duration)
  }

  def shutdown() {
    IO(Http)(context).tell(Http.Unbind, server)
    context.stop(server)
    context.stop(allConnectionsHandler)
  }

  def extractRequestList(httpResponseFuture: Future[HttpResponse]): Future[List[Int]] = {
    implicit val execContext = context.dispatcher

    httpResponseFuture flatMap {
      response =>
        if(response.status == StatusCodes.OK) {
          val responseData = response.entity.data.asString
          if(responseData.isEmpty)
            Future.successful(List())
          else
            Future.successful(responseData.split(",").map(_.toInt).toList)
        } else {
          Future.failed[List[Int]](new UnsuccessfulResponseException(response.status))
        }
    }
  }
}