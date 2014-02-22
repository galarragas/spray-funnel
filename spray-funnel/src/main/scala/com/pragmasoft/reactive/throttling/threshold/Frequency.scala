package com.pragmasoft.reactive.throttling.threshold

import scala.concurrent.duration._

case class Frequency(amount: Int, interval: FiniteDuration) {
  override def toString : String = s"$amount every $interval"
}

trait FrequencyConversions extends Any {
  protected def forInterval(interval: FiniteDuration) : Frequency

  def every(interval: FiniteDuration) : Frequency = forInterval(interval)

  def perSecond = every(1 second)
  def perMinute = every(1 minute)
  def perHour = every(1 hour)
}

