package com.pragmasoft.reactive.throttling.http

import akka.actor._
import scala.concurrent.duration._
import com.pragmasoft.reactive.throttling.actors.handlerspool.{FixedSizePool, HandlerFactory}
import com.pragmasoft.reactive.throttling.threshold.Frequency
import scala.concurrent.ExecutionContext
import akka.util.Timeout
import com.pragmasoft.reactive.throttling.http.HttpRequestReplyCoordinator._



object HttpRequestThrottling {

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
   * @param transport The transport taking care of the request
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
   * @param transport The transport taking care of the request
   * @param actorSystem The Actor System the new agent has to be created in
   * @param requestTimeout The Timeout to be used when waiting for responses from the transport
   * @return
   */
  def throttleFrequencyWithTransport(frequencyThreshold: Frequency, transport: ActorRef)
                                                     (implicit actorSystem : ActorSystem, requestTimeout: Timeout = 60.seconds) : ActorRef =
    actorSystem.actorOf(propsForFrequencyWithTransport(frequencyThreshold, transport, requestTimeout))

}


