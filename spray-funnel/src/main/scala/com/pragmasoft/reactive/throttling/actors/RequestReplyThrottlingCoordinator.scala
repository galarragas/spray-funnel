package com.pragmasoft.reactive.throttling.actors

import akka.actor._
import akka.dispatch.AbstractNodeQueue
import akka.actor.SupervisorStrategy.Resume
import scala.concurrent.duration._
import com.pragmasoft.reactive.throttling.actors.handlerspool.ActorPool
import scala.Some
import akka.actor.OneForOneStrategy
import com.pragmasoft.reactive.throttling.threshold.Frequency

object IntervalExpired
object Ready
case class ClientRequest[Request](request: Request, client: ActorRef, transport: ActorRef, callTimeout : FiniteDuration)

// Features to add:
// - Retry a request after some time (how? what time) this on top of Spray retry
// - Limit per SOURCE IP (to support client clustering)

abstract class RequestReplyThrottlingCoordinator[Request](
        targetRequestHandler: ActorRef,
        frequencyThreshold: Frequency,
        requestTimeout: FiniteDuration,
        requestExpiry: Duration,
        maxQueueSize: Int
     )(implicit manifest: Manifest[Request]) extends Actor with ActorLogging {

  case class ExpiryingClientRequest(clientRequest: ClientRequest[Request], expiry: Long) {
    def isExpired : Boolean = {
       if(expiry > 0)
         System.currentTimeMillis > expiry
       else
         false
    }
  }

  import context.dispatcher

  def handlersPool : ActorPool

  class RequestQueue extends AbstractNodeQueue[ExpiryingClientRequest]

  val requestsToServe = new RequestQueue

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
      case _: Exception                => Resume
    }

  context.watch(targetRequestHandler)

  var leftRequestAllowanceForThisInterval = frequencyThreshold.amount

  context.system.scheduler.schedule(frequencyThreshold.interval, frequencyThreshold.interval, self, IntervalExpired)

  override def receive: Actor.Receive = {
    case Ready =>
      log.debug("Actor {} finished its work", sender)
      handlersPool putBack sender
      tryToServeRequest()

    case IntervalExpired =>
      leftRequestAllowanceForThisInterval = frequencyThreshold.amount
      tryToServeRequest()

    case request if(manifest.runtimeClass.isAssignableFrom(request.getClass) ) =>
      val clientRequest = ClientRequest(request.asInstanceOf[Request], sender, targetRequestHandler, requestTimeout)
      if( isUnboundQueue || (requestsToServe.count() < maxQueueSize) ) {
        log.debug("Received request {}", request)
        requestsToServe.add( ExpiryingClientRequest(clientRequest, getMessageExpiry) )
        tryToServeRequest()
      } else {
        log.debug( "Discarding request {}. Queue has size {} greater than allowed of {}", request, requestsToServe.count(), maxQueueSize )
        requestRefused(clientRequest)
      }

    case Terminated(`targetRequestHandler`) =>
        log.debug("Transport terminated. Shutting down")
        context.stop(self)
  }

  def isUnboundQueue : Boolean =  (maxQueueSize <= 0) || (maxQueueSize == Int.MaxValue)

  def getMessageExpiry : Long = {
    if(requestExpiry == Duration.Inf) 0 else System.currentTimeMillis + requestExpiry.toMillis
  }

  def nextRequest() : Option[ClientRequest[Request]] = {
    if(requestsToServe.isEmpty) {
      None
    } else {
      val currRequest = requestsToServe.poll()
      if(!currRequest.isExpired)
        Some(currRequest.clientRequest)
      else {
        log.debug(s"Request $currRequest is expired, discarding it")
        requestExpired(currRequest.clientRequest)
        nextRequest()
      }
    }
  }

  def requestExpired(clientRequest: ClientRequest[Request]) : Unit

  def requestRefused(clientRequest: ClientRequest[Request]) : Unit

  def tryToServeRequest() : Unit = {
    if( (leftRequestAllowanceForThisInterval > 0) && !handlersPool.isEmpty ) {
      nextRequest() match {
        case Some(request) =>
          val handler = handlersPool.get()
          log.debug("Serving request {} with handler {}", request, handler)
          handler ! request
          leftRequestAllowanceForThisInterval -= 1
        case None =>
          log.debug(s"No messages left to be sent and $leftRequestAllowanceForThisInterval messages still allowed to be sent in this interval")
      }
    } else {
      if(handlersPool.isEmpty)
        log.debug(s"No handlers available to serve request having ${requestsToServe.count()} to be sent and $leftRequestAllowanceForThisInterval messages still allowed to be sent in this interval")
      else
        log.debug("Not sending message: leftRequestAllowanceForThisMinute: {}, #requestToServe: {}", leftRequestAllowanceForThisInterval, requestsToServe.count())
    }
  }

  override def postStop(): Unit = {
    handlersPool.shutdown()
  }

}
