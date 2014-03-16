package com.pragmasoft.reactive.throttling

import scala.concurrent.duration._
import scala.util.{Failure, Try}
import org.specs2.matcher.MatchResult

package object util  {

  def withinTimeout[T](timeout: Duration)(assertion: => MatchResult[T]) : MatchResult[T] = {
    def isSuccessful(test: Try[MatchResult[T]]) : Boolean = (test.isSuccess && test.get.isSuccess)

    val until = System.currentTimeMillis + timeout.toMillis
    var lastTry : Try[MatchResult[T]] = Try { assertion }
    do {
      if(!isSuccessful(lastTry)) {
        Thread.sleep(timeout.toMillis / 10)
        lastTry = Try { assertion }
      }
    } while( (System.currentTimeMillis <= until) && !isSuccessful(lastTry))

    lastTry.get
  }
}
