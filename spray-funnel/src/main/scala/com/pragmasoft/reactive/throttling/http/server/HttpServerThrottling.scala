package com.pragmasoft.reactive.throttling.http.server

import akka.actor._
import scala.concurrent.duration._
import com.pragmasoft.reactive.throttling.threshold.Frequency
import akka.util.Timeout
import com.pragmasoft.reactive.throttling.http._
import com.pragmasoft.reactive.throttling.http.server.HttpServerThrottlingCoordinator._

object HttpServerThrottling {

  /**
   * Creates a Quality of Service Actor forwarding every HttpRequest to the specified actor
   * limiting the frequency and number of parallel request passing through it.
   *
   * It allocates a pool of actor with size equal to the parameter maxParallelRequests to handle the requests
   *
   * Every message not sub-class of HttpRequest will be rejected. Any response of type not extending HttpResponse
   * will be discarded
   *
   * @param frequencyThreshold Maximum frequency of requests forwarded to the transport
   * @param maxParallelRequests Maximum number of active requests through this channel
   * @param httpServer The actor taking care of the requests
   * @param actorSystem The Actor System the new agent has to be created in
   * @param requestTimeout The Timeout to be used when waiting for responses from the transport
   * @return
   */
  def throttleFrequencyAndParallelRequest(frequencyThreshold: Frequency, maxParallelRequests: Int)(httpServer: ActorRef)
                                                     (implicit actorSystem : ActorSystem, requestTimeout: Timeout = 60.seconds) : ActorRef =
    actorSystem.actorOf(propsForFrequencyAndParallelRequests(frequencyThreshold, maxParallelRequests, requestTimeout, httpServer))

  /**
   * Creates a Quality of Service Actor forwarding every HttpRequest to the specified actor
   * limiting the frequency of the requests passing through it. It won't limit the number of parallel requests.
   * It creates a new actor per request
   *
   * Every message not sub-class of HttpRequest will be rejected. Any response of type not extending HttpResponse
   * will be discarded
   *
   * @param frequencyThreshold Maximum frequency of requests forwarded to the transport
   * @param httpServer The actor taking care of the request
   * @param actorSystem The Actor System the new agent has to be created in
   * @param requestTimeout The Timeout to be used when waiting for responses from the transport
   * @return
   */
  def throttleFrequency(frequencyThreshold: Frequency)(httpServer: ActorRef)
                                                     (implicit actorSystem : ActorSystem, requestTimeout: Timeout = 60.seconds) : ActorRef =
    actorSystem.actorOf(propsForFrequency(frequencyThreshold, requestTimeout, httpServer))


  /**
   * Creates a Quality of Service Actor orwarding every HttpRequest to the to the specified actor
   * limiting the frequency of the requests passing through it, the max number of parallel requests, the expiration time
   * after which unserved requests will be discarded and the maximum number of unserved messages after which any new request will
   * be discarded until the queue depth will become lower than the threshold.
   *
   * @param config            Threshold configuration
   * @param actorSystem       The Actor System the new agent has to be created in
   * @return
   */
  def throttleWithConfig(config: HttpThrottlingConfiguration)(httpServer: ActorRef)(implicit actorSystem : ActorSystem) : ActorRef =
    actorSystem.actorOf(propsForConfig(config, httpServer))
}


