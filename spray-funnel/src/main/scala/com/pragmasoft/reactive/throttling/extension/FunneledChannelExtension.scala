package com.pragmasoft.reactive.throttling.extension

import akka.actor.{ExtensionKey, ActorRefFactory, ActorRef, ExtendedActorSystem}
import com.pragmasoft.reactive.throttling.threshold.Frequency
import scala.concurrent.ExecutionContext
import akka.util.Timeout
import scala.concurrent.duration._
import com.pragmasoft.reactive.throttling.http.HttpRequestReplyCoordinator._
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
  val maxParallelRequests = extensionConfig getInt "parallel.requests"
  val timeoutDuration = extensionConfig getDuration "timeout"

  require(frequencyDuration.isFinite, "Need to specify a finite interval for 'frequency.interval'")
  require(timeoutDuration.isFinite, "Need to specify a finite interval for 'timeout'")

  val frequencyInterval : FiniteDuration = frequencyDuration.toMillis millis
  implicit val timeout : Timeout = timeoutDuration.toMillis millis

  implicit val refFactory : ActorRefFactory = system
  import refFactory.dispatcher

  override def manager: ActorRef =
    system.actorOf(
      if(maxParallelRequests > 0)
        propsForFrequencyAndParallelRequests( frequencyThreshold every frequencyInterval,  maxParallelRequests )
      else
        propsForFrequency( frequencyThreshold every frequencyInterval ),

      extensionName
    )
}

