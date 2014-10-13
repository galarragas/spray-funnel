package com.pragmasoft.reactive.throttling.http.server

import akka.actor.{ActorSystem, ActorRef}
import spray.routing.Route
import com.pragmasoft.reactive.throttling.util.stubserver.StubServerSupport
import com.typesafe.config.Config
import org.specs2.execute.{Result, AsResult}
import org.specs2.mutable.Around
import spray.client.UnsuccessfulResponseException
import spray.util.Utils
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import spray.client.pipelining._
import scala.concurrent.{ExecutionContext, Future}
import spray.http.{HttpEntity, StatusCodes, HttpResponse}
import com.pragmasoft.reactive.throttling.util.stubserver._
import ExecutionContext.Implicits.global

class WithStubbedApi(
                      throttlingWrappingFactory: (ActorRef, ActorSystem)  => ActorRef, 
                      serverResponseDelay: FiniteDuration = 0 millis,
                      extraRouteDef: Option[ActorSystem => Route] = None
                    )
  (implicit testConf: Config) extends Around with StubServerSupport {
  override lazy val context = ActorSystem(Utils.actorSystemNameFrom(getClass), testConf)


  def pipeline(responseExtractor: HttpEntity => Any = getResponseAsString) = sendReceive(context, context.dispatcher) ~> {
    responseFuture: Future[HttpResponse] =>  responseFuture flatMap {
      response: HttpResponse =>
        if(response.status == StatusCodes.OK) Future.successful( responseExtractor(response.entity) )
        else Future.failed(new UnsuccessfulResponseException(response.status))
    }
  }

  def getResponseAsString(httpEntity: HttpEntity): Any = httpEntity.asString

  def getResponseAsByteArray(httpResponseEntity: HttpEntity): Any =
    httpResponseEntity.data.toByteArray

  def getResponseAsStream(httpResponseEntity: HttpEntity): Any =
    httpResponseEntity.data.toChunkStream(10000)

  def callRoute(routeWithParams: String, responseExtractor: HttpEntity => Any = getResponseAsString): Future[Any] = {
    pipeline(responseExtractor) { Get(s"http://$interface:$port/$routeWithParams") }
  }

  def callService(id: Int) : Future[String] = {
    (pipeline(getResponseAsString) { Get(s"http://$interface:$port$servicePath?id=$id") }).mapTo[String]
  }

  def around[T: AsResult](t: => T): Result = {

    val (interface, port) = Utils.temporaryServerHostnameAndPort()

    val extraRoutes = extraRouteDef map { definition => definition(context)  }

    setupForServerTesting(interface, port, serverResponseDelay, throttlingWrappingFactory, extraRoutes)
    try {
      AsResult(t)
    } finally {
      shutdown()

      context.shutdown()
    }
  }

}
