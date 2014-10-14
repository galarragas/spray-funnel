package com.pragmasoft.reactive.throttling.issues.server

import akka.actor.ActorSystem
import com.pragmasoft.reactive.throttling.http.server.HttpServerThrottling._
import com.pragmasoft.reactive.throttling.http.server.WithStubbedApi
import com.pragmasoft.reactive.throttling.util._
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions
import com.pragmasoft.reactive.throttling.threshold._
import spray.http.{HttpData, MediaTypes, ContentType}
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.Future._
import scala.concurrent.Future
import scala.concurrent.Await._
import java.io.{FileInputStream, FileOutputStream, File, DataInputStream}
import org.specs2.matcher.MatchResult
import org.specs2.execute.Result

import spray.routing.Route

class Issue7ChunkedRequestNotSupported extends Specification with NoTimeConversions with RetryExamples {
  implicit val testConf = ConfigFactory.parseString(
    """
akka {
      loglevel = WARNING
      loggers = ["akka.testkit.TestEventListener"]
      log-dead-letters-during-shutdown=off
            log-config-on-start = off
            # event-stream = on
            receive = on
}

spray.can {
  client {
    user-agent-header = spray-can
    idle-timeout = 60 s
    request-timeout = 60 s
    # Set this larger than the large file to be read otherwise Spray-Client will close the connection
    response-chunk-aggregation-limit = 12m
  }
}
    """)

  import spray.routing.Directives._

  def chunkedRoute(system : ActorSystem): Route = {
    implicit val _ = system

    path("chunked") {
      autoChunk(10) {
        complete { "testchunk1testchunk2testchunk3testchunk4testchunk5" }
      }
    } ~
    path ("chunkedFile") {
      getFromResource("funnel.jpg", ContentType(MediaTypes.`image/jpeg`))
    } ~
    path ("largeChunkedFile") {
      getFromResource("Pizigani_1367_Chart_10MB.jpg", ContentType(MediaTypes.`image/jpeg`))
    }
  }

  "A throttled client" should {
    "handle chunked simple responses from server" in new WithStubbedApi(
      (actor, context) => throttleFrequency(2 every 1.second)(actor)(context),
      0.millis,
      Some(chunkedRoute)
    ) {
      val resourceContent = result(callRoute("chunked"), 5.seconds)

      resourceContent shouldEqual "testchunk1testchunk2testchunk3testchunk4testchunk5"
    }

    "handle chunked resources served by server" in new WithStubbedApi(
      (actor, context) => throttleFrequency(2 every 1.second)(actor)(context),
      0.millis,
      Some(chunkedRoute)
    ) {
      val resourceContent = result(callRoute("chunkedFile", getResponseAsByteArray ), 15.seconds)

      val file = new File( getClass.getClassLoader.getResource("funnel.jpg").getFile )
      val fileData = new Array[Byte](file.length.toInt)
      val dis = new DataInputStream(new FileInputStream(file))
      dis.readFully(fileData)
      dis.close()

      resourceContent shouldEqual fileData
    }

    "handle chunked resources served by server - large file" in new WithStubbedApi(
      (actor, context) => throttleFrequency(2 every 1.second)(actor)(context),
      0.millis,
      Some(chunkedRoute)
    ) {
      acquireAndCompareResource(
        result(callRoute("largeChunkedFile", getResponseAsStream).mapTo[Stream[HttpData]], 3.minutes),
        "Pizigani_1367_Chart_10MB.jpg"
      )
    }

    "handle chunked resources served by server - many parallel requests" in new WithStubbedApi(
      (actor, context) => throttleFrequency(2 every 1.second)(actor)(context),
      0.millis,
      Some(chunkedRoute)
    ) {
      val testFutures = (1 to 15) map { idx =>
        val (resourceName, fileName) =
          if( (idx % 3) != 0)
            ("chunkedFile", "funnel.jpg")
          else
            ("largeChunkedFile", "Pizigani_1367_Chart_10MB.jpg")

        Future(
          acquireAndCompareResource(
            result(callRoute(resourceName, getResponseAsStream).mapTo[Stream[HttpData]], 3.minutes),
            fileName
          )
        )
      }

      val testResults = Await.result( sequence(testFutures), 30.seconds )

      testResults.fold(success) { (currResult, currElem) => currResult and currElem  }
    }
  }

  def acquireAndCompareResource(acquireData: => Stream[HttpData], compareToResourceName: String): Result = {
    val resourceContentStream = acquireData

    val acquiredFile = File.createTempFile("downloadChunk", ".jpg")
    acquiredFile.deleteOnExit()

    val acquiredFileWriter = new FileOutputStream(acquiredFile)

    resourceContentStream foreach { currChunk =>
      acquiredFileWriter.write(currChunk.toByteArray)
    }

    acquiredFileWriter.close()

    val file = new File( getClass.getClassLoader.getResource(compareToResourceName).getFile )
    val originFileStream = new DataInputStream(new FileInputStream(file))
    val acquiredFileStream = new DataInputStream( new FileInputStream(acquiredFile) )

    val originFileBuf = new Array[Byte](1000)
    val acquiredFileBuf = new Array[Byte](1000)

    var matchResult: Result = success
    try {
      var finished = false

      do {
        val origLen = originFileStream.read(originFileBuf)
        val acquiredLen = acquiredFileStream.read(acquiredFileBuf)

        matchResult = (originFileBuf.take(origLen) shouldEqual acquiredFileBuf.take(acquiredLen))

        finished = (origLen <= 0)
      } while(!finished && matchResult.isSuccess)
    } finally {
      originFileStream.close()
      acquiredFileStream.close()
    }

    matchResult
  }

}
