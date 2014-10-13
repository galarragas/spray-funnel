package com.pragmasoft.reactive.sprayfunnel.example.client

import scala.concurrent.Future
import spray.http._
import spray.client.pipelining._
import com.pragmasoft.reactive.throttling.threshold.Frequency._
import com.pragmasoft.reactive.throttling.http.client.HttpClientThrottling
import HttpClientThrottling._
import com.pragmasoft.reactive.throttling.threshold._
import akka.actor.ActorSystem
import scala.concurrent.duration._
import akka.util.Timeout

object SimpleClient extends App {

  implicit val actorSystem = ActorSystem("TestActorSystem")
  import actorSystem.dispatcher

  implicit val timeout : Timeout = 30 seconds

  val pipeline: String => Future[HttpResponse] =
    requestForEmail _ ~> logRequest( x => logARequest(x) ) ~> sendReceive(throttleFrequency(20 perSecond)) ~> logResponse(x => logAResponse(x))

  val email = readLine("Please enter email to send: ")
  val httpResponse = pipeline ( email )

  httpResponse.onComplete { _ => actorSystem.shutdown() }

  def requestForEmail(email: String) : HttpRequest = Get("http://www.google.com")

  def logARequest(request: HttpRequest) : Unit = println(request)
  def logAResponse(response: HttpResponse) : Unit = println(response)

}
