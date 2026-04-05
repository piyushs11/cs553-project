package edu.uic.cs553.runtime

import akka.actor.{ActorRef, ActorSystem}
import edu.uic.cs553.core.{EnrichedGraph, SimConfig}

/** Wires an EnrichedGraph into a live ActorSystem.
 *  Each graph node becomes a NodeActor, each edge becomes a channel. */
object Simulator:

  /** Build actors from the graph and send Init to each.
   *  Returns a map of nodeId → ActorRef for external use (injection, queries). */
  def buildFrom(
    graph: EnrichedGraph,
    config: SimConfig,
    algorithms: List[DistributedAlgorithm],
    system: ActorSystem
  ): Map[Int, ActorRef] =

    // Step 1: Create one actor per node
    val refs: Map[Int, ActorRef] = graph.nodes.map { n =>
      n.id -> system.actorOf(NodeActor.props(n.id, algorithms, config.seed), s"node-${n.id}")
    }.toMap

    // Step 2: Send Init to each actor with its neighbor wiring
    graph.nodes.foreach { n =>
      val outgoing = graph.neighborsOf(n.id)

      val neighborRefs: Map[Int, ActorRef] =
        outgoing.flatMap(e => refs.get(e.to).map(e.to -> _)).toMap

      val allowedOnEdge: Map[Int, Set[String]] =
        outgoing.map(e => e.to -> e.allowedTypes).toMap

      val edgeWeights: Map[Int, Double] =
        outgoing.map(e => e.to -> e.weight).toMap

      refs(n.id) ! SimMessage.Init(
        neighbors = neighborRefs,
        allowedOnEdge = allowedOnEdge,
        edgeWeights = edgeWeights,
        pdf = n.pdf,
        timerEnabled = config.isTimerNode(n.id),
        tickEveryMs = config.tickEveryMs(n.id)
      )
    }

    system.log.info(s"Simulator: ${refs.size} actors created and initialized")
    refs

  /** Send StartAlgorithms to all nodes. */
  def startAlgorithms(refs: Map[Int, ActorRef]): Unit =
    refs.values.foreach(_ ! SimMessage.StartAlgorithms)