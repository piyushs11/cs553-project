package edu.uic.cs553.runtime

import akka.actor.ActorRef

/**
 * Sealed message hierarchy for the simulation runtime.
 *
 * Design rationale: Using a sealed trait with case classes gives us
 * exhaustive pattern matching and avoids the stringly-typed message
 * penalty. Algorithm-specific messages extend
 * AlgorithmMsg so they flow through the same dispatch pipeline.
 */
sealed trait SimMessage

object SimMessage:

  /** Sent once to each NodeActor after creation to wire up neighbors,
   *  edge constraints, PDF, and timer configuration. */
  final case class Init(
    neighbors: Map[Int, ActorRef],
    allowedOnEdge: Map[Int, Set[String]],
    edgeWeights: Map[Int, Double],
    pdf: Map[String, Double],
    timerEnabled: Boolean,
    tickEveryMs: Long
  ) extends SimMessage

  /** Application-level message routed between nodes.
   *  Edge label enforcement checks `kind` against allowedOnEdge before delivery. */
  final case class Envelope(from: Int, kind: String, payload: String) extends SimMessage

  /** External stimulus injected by the CLI driver into input nodes. */
  final case class ExternalInput(kind: String, payload: String) extends SimMessage

  /** Internal timer tick — never sent across the network. */
  private[runtime] case object Tick extends SimMessage

  /** Wrapper for algorithm-specific messages.
   *  The `algorithmName` field routes to the correct plugin module. */
  final case class AlgorithmMsg(
    algorithmName: String,
    from: Int,
    payload: Any
  ) extends SimMessage

  /** Signals algorithms to begin execution. */
  case object StartAlgorithms extends SimMessage

  /** Requests a state dump from the node (for testing and monitoring). */
  final case class GetState(replyTo: ActorRef) extends SimMessage

  /** Response to GetState. */
  final case class StateResponse(nodeId: Int, state: Map[String, Any]) extends SimMessage