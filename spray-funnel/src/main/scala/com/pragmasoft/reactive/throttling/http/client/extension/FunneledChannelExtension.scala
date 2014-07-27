package com.pragmasoft.reactive.throttling.http.client.extension

import akka.actor._
import com.pragmasoft.reactive.throttling.http.{RequestThrottlingConfiguration, HttpThrottlingConfiguration}
import com.pragmasoft.reactive.throttling.threshold.Frequency
import scala.concurrent.ExecutionContext
import akka.util.Timeout
import scala.concurrent.duration._
import com.pragmasoft.reactive.throttling.http.client.HttpClientThrottlingCoordinator._
import com.pragmasoft.reactive.throttling.threshold._
import spray.can.HttpExt
import spray.util._

trait FunneledChannelExtension extends akka.io.IO.Extension {
  def configRootName : String
  def system: ExtendedActorSystem

  /**
   * Extend this method to give a different name to the extension manager actor
   * @return
   */
  def extensionName : String = getClass.getName

  val extensionConfig = system.settings.config getConfig configRootName

  val frequencyThreshold = extensionConfig getInt "frequency.threshold"
  val frequencyDuration = extensionConfig getDuration  "frequency.interval"
  val maxParallelRequests = extensionConfig getPossiblyInfiniteInt "requests.parallel-threshold"
  val timeoutDuration = extensionConfig getDuration "requests.timeout"
  val maxQueueSize =  if(extensionConfig.hasPath("requests.max-queue-size")) extensionConfig getPossiblyInfiniteInt "requests.max-queue-size" else Int.MaxValue
  val requestExpiriy = if(extensionConfig.hasPath("requests.expiry")) extensionConfig getDuration "requests.expiry" else Duration.Inf

  require(frequencyDuration.isFinite, "Need to specify a finite interval for 'frequency.interval'")
  require(timeoutDuration.isFinite, "Need to specify a finite interval for 'requests.timeout'")

  val frequencyInterval : FiniteDuration = frequencyDuration.toMillis millis
  implicit val timeout : Timeout = timeoutDuration.toMillis millis

  implicit val refFactory : ActorSystem = system
  import refFactory.dispatcher

  override def manager: ActorRef =
    system.actorOf(
      propsForConfig(
        HttpThrottlingConfiguration(
          frequencyThreshold every frequencyInterval,
          RequestThrottlingConfiguration(
            if ((maxParallelRequests > 0) && (maxParallelRequests != Int.MaxValue)) maxParallelRequests else 0,
            timeout,
            requestExpiriy,
            maxQueueSize
          )
        )
      ),
      extensionName
    )
}

