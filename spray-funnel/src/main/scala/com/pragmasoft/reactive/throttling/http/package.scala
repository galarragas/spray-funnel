package com.pragmasoft.reactive.throttling

import akka.util.Timeout
import scala.concurrent.duration._
import com.pragmasoft.reactive.throttling.threshold.Frequency

package object http {
  object DiscardReason extends Enumeration {
    type DiscardReason = Value
    val Expired, QueueThresholdReached = Value
  }

  object FailureReason extends Enumeration {
    type FailureReason = Value
    val Timeout = Value
  }

  import DiscardReason._
  import FailureReason._

  case class DiscardedClientRequest[Request](reason: DiscardReason, request: Request)
  case class FailedClientRequest[Request](reason: FailureReason, request: Request)


  /**
   * Configuration related to the Request Throttling parameters
   *
   * @param parallelThreshold  Max number of request active at the same time on this channel.
   *                           parallel-threshold = infinite disables parallel request limit
   *                           Defaults to unlimited
   *                           Values <= 0 and == Int.MaxValue means unlimited
   * @param timeout            Max timeout waiting for the response of any request.
   *                           Should be a finite value.
   *                           Defaults to 60 seconds
   * @param expiry             Interval after which not served request will be discarded
   *                           Defaults to Duration.Inf => no expiry
   * @param maxQueueSize       If set to a finite value will cause to discard all messages received when t
   *                           he queue of not served messages is higher than the threshold
   *                           Defaults to umlimited
   *                           Values <= 0 and Int.MaxValue mean infinite size
   */
  case class RequestThrottlingConfiguration(
                                             parallelThreshold: Int = 0,
                                             timeout : Timeout = 60.seconds,
                                             expiry : Duration = Duration.Inf,
                                             maxQueueSize : Int = Int.MaxValue
                                             )

  /**
   * @param frequencyThreshold  Frequency threshold
   * @param requestConfig       Request level configuration @see RequestThrottlingConfiguration
   */
  case class HttpThrottlingConfiguration(
                                          frequencyThreshold: Frequency,
                                          requestConfig : RequestThrottlingConfiguration = RequestThrottlingConfiguration()
                                          )
}