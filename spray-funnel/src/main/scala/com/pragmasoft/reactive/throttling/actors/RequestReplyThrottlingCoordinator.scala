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
// - Max time of execution. Drop request not served after the specified time
// - Max queue size, refuse messages after the queue size arrived at the limit
// - Limit per SOURCE IP (to support client clustering)

abstract class RequestReplyThrottlingCoordinator[Request](
        transport: ActorRef,
        frequencyThreshold: Frequency,
        requestTimeout: FiniteDuration
     )(implicit manifest: Manifest[Request]) extends Actor with ActorLogging {

  import context.dispatcher

  def handlersPool : RequestHandlersPool

  class RequestQueue extends AbstractNodeQueue[ClientRequest[Request]]

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
      tryToServeRequest

    case IntervalExpired =>
      leftRequestAllowanceForThisInterval = frequencyThreshold.amount
      tryToServeRequest

    case request if(manifest.runtimeClass.isAssignableFrom(request.getClass) ) =>
      requestsToServe.add( ClientRequest(request.asInstanceOf[Request], sender, transport, requestTimeout) )
      tryToServeRequest

  }

  def tryToServeRequest : Unit = {
    if( (leftRequestAllowanceForThisInterval > 0) && !requestsToServe.isEmpty) {
      if( !handlersPool.isEmpty ) {
        handlersPool.get() ! requestsToServe.poll()
        leftRequestAllowanceForThisInterval -= 1
      } else {
        log.debug(s"No handlers available to serve request having ${requestsToServe.count()} to be sent and $leftRequestAllowanceForThisInterval messages still allowed to be sent in this interval")
      }
    } else {
      log.debug("Not sending message: leftRequestAllowanceForThisMinute: {}, #requestToServe: {}", leftRequestAllowanceForThisInterval, requestsToServe.count())
    }

  }

}
