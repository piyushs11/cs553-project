package edu.uic.cs553.runtime

import akka.actor.ActorRef
import akka.event.LoggingAdapter

/**
 * Context object passed to algorithm plugins by the NodeActor.
 *
 * Design rationale: Algorithms should not need direct access to
 * the Actor internals (ActorContext, self reference, etc.). Instead,
 * NodeContext provides a clean API for sending messages, querying
 * neighbors, and logging. This decouples algorithm logic from the
 * Akka framework, making algorithms easier to test and reason about.
 *
 * @param nodeId       this node's unique integer ID
 * @param neighbors    map of neighbor ID → ActorRef
 * @param edgeWeights  map of neighbor ID → edge weight (for GHS)
 * @param selfRef      this actor's ActorRef (for including as sender)
 * @param log          Akka logging adapter
 */
final class NodeContext(
  val nodeId: Int,
  val neighbors: Map[Int, ActorRef],
  val edgeWeights: Map[Int, Double],
  private val selfRef: ActorRef,
  private val log: LoggingAdapter,
  private val allowedOnEdge: Map[Int, Set[String]]
):

  /** Send an algorithm message to a specific neighbor.
   *  The message is wrapped in AlgorithmMsg for routing. */
  def send(to: Int, algorithmName: String, payload: Any): Unit =
    neighbors.get(to) match
      case Some(ref) => ref ! SimMessage.AlgorithmMsg(algorithmName, nodeId, payload)
      case None => log.warning(s"[node-$nodeId] Cannot send to node $to — not a neighbor")

  /** Send an algorithm message back to ourselves (used for deferred messages).
   *  This is essential for GHS when a message can't be processed yet. */
  def sendSelf(algorithmName: String, payload: Any): Unit =
    selfRef ! SimMessage.AlgorithmMsg(algorithmName, nodeId, payload)
    
  /** Send an algorithm message to ALL neighbors. */
  def broadcast(algorithmName: String, payload: Any): Unit =
    neighbors.foreach { (id, ref) =>
      ref ! SimMessage.AlgorithmMsg(algorithmName, nodeId, payload)
    }

  /** Send an algorithm message to all neighbors EXCEPT the specified one.
   *  Common pattern: forward a wave to everyone except the sender. */
  def broadcastExcept(algorithmName: String, payload: Any, except: Int): Unit =
    neighbors.foreach { (id, ref) =>
      if id != except then
        ref ! SimMessage.AlgorithmMsg(algorithmName, nodeId, payload)
    }

  /** Get the edge weight to a specific neighbor (used by GHS). */
  def weightTo(neighborId: Int): Double =
    edgeWeights.getOrElse(neighborId, Double.MaxValue)

  /** Get the number of neighbors. */
  def neighborCount: Int = neighbors.size

  /** Get all neighbor IDs. */
  def neighborIds: Set[Int] = neighbors.keySet

  /** Log an info-level message with node ID prefix. */
  def logInfo(msg: String): Unit = log.info(s"[node-$nodeId] $msg")

  /** Log a warning-level message with node ID prefix. */
  def logWarning(msg: String): Unit = log.warning(s"[node-$nodeId] $msg")

  /** Log a debug-level message with node ID prefix. */
  def logDebug(msg: String): Unit = log.debug(s"[node-$nodeId] $msg")