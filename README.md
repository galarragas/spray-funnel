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

There are two main types of usage of the library: creating a throtteling actor during the pipeline definition to wrap the HTTP transport or using AKKA extensions

### Inline Wrapping of HTTP Actor Passed to `sendReceive`

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

### Using AKKA Extensions

This mechanism allows the same throtteling channel to be shared by different pipelines, thus allowing to limit the
throughput of an application talking with destinations shared by different client classes or traits.

To enable this feature you need to create an AKKA extension. This is very simple and is just a matter of implementing
two classes as in the example below:

```scala
class TestFunneledChannelExtension(val system: ExtendedActorSystem) extends FunneledChannelExtension {
  lazy val configRootName = "qos.channels.channel1"
}

object TestFunneledChannel extends ExtensionKey[TestFunneledChannelExtension]
```

Having defined the extension the Spray Client code will be written as follows:

```scala
class SimpleSprayClient(serverBaseAddress: String, timeout : Timeout ) {

  import SimpleClientProtocol._

  implicit val actorSystem = ActorSystem("program-info-client", ConfigFactory.parseResources("test.conf"))
  import actorSystem.dispatcher

  implicit val futureTimeout : Timeout = timeout

  val pipeline = sendReceive(IO(TestFunneledChannel)) ~> unmarshal[SimpleResponse]

  def callFakeService(id: Int) : Future[SimpleResponse] = pipeline { Get(s"$serverBaseAddress/fakeService?$id") }

  def shutdown() = actorSystem.shutdown()
}
```

The reference to `IO(TestFunneledChannel)` allows AKKA to retrieve the configuration of your channel and apply it to
limit the traffic of your pipeline

The AKKA configuration will be written as follows:

```
qos.channels {
    channel1 {
        frequency {
            threshold = 5
            interval = 15 s
        }
        # parallel.requests = 0 disables parallel request limit
        parallel.requests = 3
        timeout = 45 s
    }
}
```

## Dependencies:

- Scala 2.10
- Spray Client 1.2.0
- Akka 2.2.3
- Akka_testkit 2.2.3
- ScalaTest 2.10
- WireMock 1.38 - For HTTP Testing
