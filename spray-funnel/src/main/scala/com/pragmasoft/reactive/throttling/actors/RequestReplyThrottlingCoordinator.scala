package com.pragmasoft.reactive.throttling.actors

import akka.actor.{OneForOneStrategy, ActorLogging, Actor, ActorRef}
import scala.concurrent.duration.FiniteDuration
import akka.dispatch.AbstractNodeQueue
import akka.actor.SupervisorStrategy.Resume
import scala.concurrent.duration._
import com.pragmasoft.reactive.throttling.actors.handlerspool.RequestHandlersPool
import com.pragmasoft.reactive.throttling.threshold.Frequency

object IntervalExpired
object Ready
case class ClientRequest[Request](request: Request, client: ActorRef, transport: ActorRef, callTimeout : FiniteDuration)

// Features to add:
// - Retry a request after some time (how? what time) this on top of Spray retry
// - Limit per SOURCE IP (to support client clustering)

// - Max time of execution. Drop request not served after the specified time
// - Max queue size, refuse messages after the queue size arrived at the limit

abstract class RequestReplyThrottlingCoordinator[Request](
        transport: ActorRef,
        frequencyThreshold: Frequency,
        requestTimeout: FiniteDuration,
        requestExpiry: Duration,
        maxQueueSize: Int
     )(implicit manifest: Manifest[Request]) extends Actor with ActorLogging {

  case class ExpiryingClientRequest(request: ClientRequest[Request], expiry: Long) {
    def isExpired : Boolean = {
       if(expiry > 0)
         System.currentTimeMillis > expiry
       else
         false
    }
  }

  import context.dispatcher

  def handlersPool : RequestHandlersPool

  class RequestQueue extends AbstractNodeQueue[ExpiryingClientRequest]

  val requestsToServe = new RequestQueue

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
      case _: Exception                => Resume
    }

  var leftRequestAllowanceForThisInterval = frequencyThreshold.amount

  context.system.scheduler.schedule(frequencyThreshold.interval, frequencyThreshold.interval, self, IntervalExpired)

  override def receive: Actor.Receive = {
    case Ready =>
      handlersPool putBack sender
      tryToServeRequest()

    case IntervalExpired =>
      leftRequestAllowanceForThisInterval = frequencyThreshold.amount
      tryToServeRequest()

    case request if(manifest.runtimeClass.isAssignableFrom(request.getClass) ) =>
      if( (maxQueueSize <= 0) || (requestsToServe.count() < maxQueueSize) ) {
        requestsToServe.add(
           ExpiryingClientRequest(
             ClientRequest(request.asInstanceOf[Request], sender, transport, requestTimeout),
             getMessageExpiry
           )
         )
        tryToServeRequest()
      } else {
        log.warning( "Discarding request {}. Queue has size {} greater than allowed of {}", request, requestsToServe.count(), maxQueueSize )
      }

  }

  def getMessageExpiry : Long = {
    if(requestExpiry == Duration.Inf) 0 else System.currentTimeMillis + requestExpiry.toMillis
  }

  def nextRequest() : Option[ClientRequest[Request]] = {
    if(requestsToServe.isEmpty) {
      None
    } else {
      val currRequest = requestsToServe.poll()
      if(!currRequest.isExpired)
        Some(currRequest.request)
      else {
        log.warning(s"Request $currRequest is expired, discarding it")
        nextRequest()
      }
    }
  }

  def tryToServeRequest() : Unit = {
    if( (leftRequestAllowanceForThisInterval > 0) && !handlersPool.isEmpty ) {
      nextRequest() match {
        case Some(request) =>
          handlersPool.get() ! request
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



}
