package com.pragmasoft.reactive.throttling.http.client

import akka.actor._
import scala.concurrent.duration._
import com.pragmasoft.reactive.throttling.actors.handlerspool.{FixedSizePool, HandlerFactory}
import com.pragmasoft.reactive.throttling.threshold.Frequency
import scala.concurrent.ExecutionContext
import akka.util.Timeout
import com.pragmasoft.reactive.throttling.http._
import com.pragmasoft.reactive.throttling.http.client.HttpClientThrottlingCoordinator._

object HttpClientThrottling {

  /**
   * Creates a Quality of Service Actor forwarding every HttpRequest to the HTTP AKKA extension
   * limiting the frequency and number of parallel request passing through it.
   *
   * It allocates a pool of actor with size equal to the parameter maxParallelRequests to handle the requests
   *
   * Every message not sub-class of HttpRequest will be rejected. Any response of type not extending HttpResponse
   * will be discarded
   *
   * @param frequencyThreshold Maximum frequency of requests forwarded to the transport
   * @param maxParallelRequests Maximum number of active requests through this channel
   * @param actorSystem The Actor System the new agent has to be created in
   * @param executionContext The Execution Context to be used in handling futures
   * @param requestTimeout The Timeout to be used when waiting for responses from the transport
   * @return
   */
  def throttleFrequencyAndParallelRequests(frequencyThreshold: Frequency, maxParallelRequests: Int)
                                          (implicit actorSystem : ActorSystem, executionContext: ExecutionContext,requestTimeout: Timeout = 60.seconds) : ActorRef =
    actorSystem.actorOf(propsForFrequencyAndParallelRequests(frequencyThreshold, maxParallelRequests)(actorSystem, executionContext, requestTimeout) )


  /**
   * Creates a Quality of Service Actor forwarding every HttpRequest to the HTTP AKKA extension
   * limiting the frequency of the requests passing through it. It won't limit the number of parallel requests.
   * It creates a new actor per request
   *
   * Every message not sub-class of HttpRequest will be rejected. Any response of type not extending HttpResponse
   * will be discarded

   * @param frequencyThreshold  Maximum frequency of requests forwarded to the transport
   * @param actorSystem The Actor System the new agent has to be created in
   * @param executionContext The Execution Context to be used in handling futures
   * @param requestTimeout The Timeout to be used when waiting for responses from the transport
   * @return
   */
  def throttleFrequency(frequencyThreshold: Frequency)
                       (implicit actorSystem : ActorSystem, executionContext: ExecutionContext, requestTimeout: Timeout = 60.seconds) : ActorRef =
    actorSystem.actorOf(propsForFrequency(frequencyThreshold)(actorSystem, executionContext, requestTimeout) )


  /**
   * Creates a Quality of Service Actor orwarding every HttpRequest to the HTTP AKKA extension
   * limiting the frequency of the requests passing through it, the max number of parallel requests, the expiration time
   * after which unserved requests will be discarded and the maximum number of unserved messages after which any new request will
   * be discarded until the queue depth will become lower than the threshold.
   *
   * @param config            Threshold configuration
   * @param actorSystem       The Actor System the new agent has to be created in
   * @param executionContext  The Execution Context to be used in handling futures
   * @return
   */
  def throttleWithConfig(config: HttpThrottlingConfiguration)
                        (implicit actorSystem : ActorSystem, executionContext: ExecutionContext) : ActorRef =
     actorSystem.actorOf(propsForConfig(config)(actorSystem, executionContext))

  /**
   * Creates a Quality of Service Actor forwarding every HttpRequest to the specified transport
   * limiting the frequency and number of parallel request passing through it.
   *
   * It allocates a pool of actor with size equal to the parameter maxParallelRequests to handle the requests
   *
   * Every message not sub-class of HttpRequest will be rejected. Any response of type not extending HttpResponse
   * will be discarded
   *
   * @param frequencyThreshold Maximum frequency of requests forwarded to the transport
   * @param maxParallelRequests Maximum number of active requests through this channel
   * @param transport The transport actor taking care of the request
   * @param actorSystem The Actor System the new agent has to be created in
   * @param requestTimeout The Timeout to be used when waiting for responses from the transport
   * @return
   */
  def throttleFrequencyAndParallelRequestWithTransport(frequencyThreshold: Frequency, maxParallelRequests: Int, transport: ActorRef)
                                                     (implicit actorSystem : ActorSystem, requestTimeout: Timeout = 60.seconds) : ActorRef =
    actorSystem.actorOf(propsForFrequencyAndParallelRequestsWithTransport(frequencyThreshold, maxParallelRequests, transport, requestTimeout))

  /**
   * Creates a Quality of Service Actor forwarding every HttpRequest to the specified transport
   * limiting the frequency of the requests passing through it. It won't limit the number of parallel requests.
   * It creates a new actor per request
   *
   * Every message not sub-class of HttpRequest will be rejected. Any response of type not extending HttpResponse
   * will be discarded
   *
   * @param frequencyThreshold Maximum frequency of requests forwarded to the transport
   * @param transport The transport actor taking care of the request
   * @param actorSystem The Actor System the new agent has to be created in
   * @param requestTimeout The Timeout to be used when waiting for responses from the transport
   * @return
   */
  def throttleFrequencyWithTransport(frequencyThreshold: Frequency, transport: ActorRef)
                                                     (implicit actorSystem : ActorSystem, requestTimeout: Timeout = 60.seconds) : ActorRef =
    actorSystem.actorOf(propsForFrequencyWithTransport(frequencyThreshold, transport, requestTimeout))


  /**
   * Creates a Quality of Service Actor orwarding every HttpRequest to the to the specified transport
   * limiting the frequency of the requests passing through it, the max number of parallel requests, the expiration time
   * after which unserved requests will be discarded and the maximum number of unserved messages after which any new request will
   * be discarded until the queue depth will become lower than the threshold.
   *
   * @param config            Threshold configuration
   * @param transport         The transport actor taking care of the request
   * @param actorSystem       The Actor System the new agent has to be created in
   * @return
   */
  def throttleWithConfigAndTransport(config: HttpThrottlingConfiguration, transport: ActorRef)
                        (implicit actorSystem : ActorSystem) : ActorRef =
    actorSystem.actorOf(propsForConfigAndTransport(config, transport))
}


