Spray Funnel
====================

Spray Client extension to allow limitation of client request frequency and number of parallel requests

[![Build Status](https://api.travis-ci.org/galarragas/spray-funnel.png)](http://travis-ci.org/galarragas/spray-funnel)

![Image](./funnel.jpg?raw=true)

## What is it?

This is a generalisation of the request throttling logic implemented in the Reactive Rest Client project on my GitHub.
The idea is to create a generic mechanism to allow the throttling of all the messages sent and received by a `sendReceive` Spray pipeline.
The work can be easily generalised for different protocols but at the moment I'm using it with for HTTP requests.

It allows to limit the output throughput of a Spray client in terms of:

- Number of request per specified interval
- Number of parallel request

As default uses the HTTP transport but offers the possibility of specifying a custom transport

## Usage

A very simple way of using this library is to specify the throttling setting in the sendReceive pipeline definition like shown below

```scala
class SimpleSprayClient(serverBaseAddress: String timeout: Timeout) {
  import SimpleClientProtocol._
  import com.pragmasoft.reactive.throttling.http.HttpRequestThrottling._

  implicit val actorSystem = ActorSystem("program-info-client", ConfigFactory.parseResources("test.conf"))

  import actorSystem.dispatcher

  implicit val apiTimeout : Timeout = timeout

  val pipeline = sendReceive(throttleFrequencyAndParallelRequests(30 perSecond, 10)) ~> unmarshal[SimpleResponse]

  def callFakeService(id: Int) : Future[SimpleResponse] = pipeline { Get(s"$serverBaseAddress/fakeService?$id") }


  def shutdown() = actorSystem.shutdown()
}
```

## Dependencies:

- Scala 2.10
- Spray Client 1.2.0
- Akka 2.2.3
- Akka_testkit 2.2.3
- ScalaTest 2.10
- WireMock 1.38 - For HTTP Testing
