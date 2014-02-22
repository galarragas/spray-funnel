package com.pragmasoft.reactive.throttling

import scala.concurrent.duration.FiniteDuration

package object threshold {

  implicit final class FrequencyInt(val amount: Int) extends AnyVal with FrequencyConversions {
    override protected def forInterval(interval: FiniteDuration): Frequency = Frequency(amount, interval)
  }
}
