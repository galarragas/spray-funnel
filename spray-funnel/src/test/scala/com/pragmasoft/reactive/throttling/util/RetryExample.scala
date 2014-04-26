package com.pragmasoft.reactive.throttling.util

import org.specs2.specification.AroundExample
import org.specs2.execute.{AsResult, Result, EventuallyResults}
import org.specs2.specification. AroundExample
import EventuallyResults._
import org.specs2.time.{DurationConversions => SpecsDurationConversions}
import scala.concurrent.duration._

trait RetryExamples extends AroundExample with SpecsDurationConversions {

  protected def around[T : AsResult](t: =>T): Result =
    AsResult { eventually(retries = 3, sleep = 40.millis)(t) }

}