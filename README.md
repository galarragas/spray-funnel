Spray Funnel
====================

Spray Client extension to allow limitation of client request frequency and number of parallel requests

[![Build Status](https://api.travis-ci.org/galarragas/spray-funnel.png)](http://travis-ci.org/galarragas/spray-funnel)

![Image](./funnel.jpg?raw=true)

## What is it?

Spray Funnel is a request throttling system for AKKA actors that has been specifically designed to support HttpRequest - HttpReply interactions.
It can be easily extended to support different protocols but at the moment is tested for HTTP-based interactions.
It can be seen as an extension of the AKKA Throttler feature (http://doc.akka.io/docs/akka/snapshot/contrib/throttle.html#introduction)
supporting Request-Reply patterns in order to provide a slightly wider set of features. It allows to limit:

- The number of request per specified interval
- Number of parallel active requests
- Timeout after which an enqueued request has to be discarded
- Maximum number of messages enqueued after which new incoming messages are discarded until the queue size decreases (limiting spikes)

It supports throttling of Spray Client code and Spray Server code

### Spray Client
The idea is to create a generic mechanism to allow the throttling of all the messages sent and received by a `sendReceive` Spray pipeline.
The work can be easily generalised for different protocols but at the moment I'm using it with for HTTP requests.

As default uses the HTTP transport but offers the possibility of specifying a custom transport

When a client request is discarded because of a timeout or because of too many enqueued requests to be served,
a notification is sent to the Actor System `eventBus` in the form of a `DiscardedClientRequest` object containing the discarded
request and the reason. If a request is not served in the specified request timeout an `FailedClientRequest` object is published
in the Actor System `eventBus`

### Spray Server

Spray Funnel can be used to limit the amount of parallel request and the frequency of request to be served by an HTTP Server Request Handler
similarly to the Jetty QoS filter (http://wiki.eclipse.org/Jetty/Reference/QoSFilter).

All requests not forwarded to the HTTP Server Request Handler because of timeout or queue threshold limit are rejected with an
`HttpResponse(BandwidthLimitExceeded)`. This will prevent the `Timedout` notification from Spray.
In a similar fashion, all requests not served by the HTTP Server Request Handler within the specified request timeout will be completed
 with a `HttpResponse(InternalServerError)` response.

## Usage

### Spray Client

There are two main types of usage of the library: creating a throttling actor during the pipeline definition to wrap the HTTP transport or using AKKA extensions

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

The object `com.pragmasoft.reactive.throttling.client.HttpClientThrottling` exports the following methods:

- `throttleFrequency` to throttle the http traffic frequency only
- `throttleFrequencyAndParallelRequests` to throttle the http traffic frequency only
- `throttleWithConfig` to specify more complex configuration (see section below about extensions to see a decription of the configuration options)

It is also possible to specify a transport different than HTTP with the methods `throttleFrequencyWithTransport`,
`throttleFrequencyAndParallelRequestWithTransport`, `throttleWithConfigAndTransport`


### Using AKKA Extensions

This mechanism allows the same throttling channel to be shared by different pipelines, thus allowing to limit the
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

  implicit val actorSystem = ActorSystem("simple-spray-client", ConfigFactory.parseResources("test.conf"))
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
        requests {
            # Max number of request active at the same time on this channel
            # parallel-threshold = infinite disables parallel request limit
            # When a request times out an event of type FailedClientRequest with parameter reason equal to Timeout
            # and a copy the discarded request is generated
            parallel-threshold = 3
            # Max timeout waiting for the response of any request. Should be a finite value
            timeout = 45 s
            # Interval after which not served request will be discarded
            # When a request is discarded an event of type DiscardedClientRequest with parameter reason equal to Expired
            # and a copy the discarded request is generated
            expiry = infinite
            # If set to a finite value will cause to discard all messages received when the queue of not served
            # messages is higher than the threshold
            # When a request is discarded an event of type DiscardedClientRequest with parameter reason equal to QueueThresholdReached
            # and a copy the discarded request is generated
            max-queue-size = infinite
        }
    }
}
```

#### Handling failures

The throttling client generates event when a requests handling has been unsuccessful. In any case spray-funnel will
publish an event on the System `eventStream` with a copy of the failed request and a description of the failure
The reason of failure and associated events are:

- The request failed because of a timeout: In this case an event of type `FailedClientRequest` with reason `Expired` is generated
- The request has been discarded according to the configuration of the channel throttler. The reasons can be two:
 - Max queue depth reached: In this case an event of type `DiscardedClientRequest` is generated with reason equal to `QueueThresholdReached`
 - Request have been in the processing queue more than the configured `expiry` parameter. In this case an event of type `DiscardedClientRequest` is generated with reason equal to `Expired`


### Spray Server

At the moment the only supported pattern is using a singleton handler, since the wrapping funneling actor is only able to
serve one target.

A sample usage is:

```scala

import com.pragmasoft.reactive.throttling.http.server.HttpServerThrottling._

class StubServer(interface: String, port: Int) extends Actor {
  IO(Http).ask(Http.Bind(service, interface, port))(3.seconds)

  val allConnectionsHandler = throttleFrequencyAndParallelRequests(30 perSecond, 10) { system.actorOf(... my http handler actor props here) }

  override def receive: Actor.Receive = {
    case Http.Connected(peer, _) ⇒
      log.debug("Connected with {}", peer)
      sender ! Http.Register(allConnectionsHandler)
  }
}

```

The object `com.pragmasoft.reactive.throttling.server.HttpServerThrottling` exports the following methods:

- `throttleFrequency` to throttle the http traffic frequency only
- `throttleFrequencyAndParallelRequests` to throttle the http traffic frequency only
- `throttleWithConfig` to specify more complex configuration (see section about client throttling with AKKA extensions to see a description of the configuration options)


### Settings already available in Spray
The parallel request limitation can be done in Spray using the `spray.can.server.pipelining-limit` parameter. This setting
will limit the number of active request per connection. The throttling available using spray-funnel instead can be used across
connections using the singleton pattern or with more sophisticated logic as for example one throttle per IP address just
using different funnels.

## Adding Dependency to Spray Funnel

Add conjars repository to your resolvers:

```
resolvers += "ConJars" at "http://conjars.org/repo",
```

then add the following dependencies to your sbt configuration

```
libraryDependencies += "com.pragmasoft" %% "spray-funnel" % "1.0-RC3"
```

## Dependencies:

Runtime:

- Scala 2.10
- Spray Client 1.2.0
- Akka 2.2.3

Test:

- Akka_testkit 2.2.3
- Specs2 2.2.3

## Support of Spray 1.3

Current version is not working propery with version 1.3 of spray (see issue #2).
Version `1.0-RC3-spray1.3` has been built with the following dependencies

- Scala 2.10/Scala 2.11
- Spray 1.3.1
- Akka 2.3.2

```
libraryDependencies += "com.pragmasoft" %% "spray-funnel" % "1.0-RC3-spray1.3"
```

## License

Copyright 2014 PragmaSoft Ltd.

Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
