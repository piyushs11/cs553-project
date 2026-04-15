package edu.uic.cs553.algorithms

import edu.uic.cs553.algorithms.GHSMessages.EdgeState

/** Per-node view of an outgoing edge.
 *  The state field mutates as the algorithm progresses.
 *  Justified: actor-local mutable state, one instance per node per edge. */
final class GHSEdge(
  val neighborId: Int,
  val weight: Double,
  var state: EdgeState = EdgeState.Basic
):
  override def toString: String = s"Edge(to=$neighborId, w=$weight, $state)"