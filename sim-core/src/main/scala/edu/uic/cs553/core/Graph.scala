package edu.uic.cs553.core

/**
 * Core graph data model for the distributed simulation.
 *
 * Design rationale: We use immutable case classes throughout to make the graph safely shareable
 * across actors. Edge weights are Double to support GHS, which requires
 * numeric weights for minimum-weight outgoing edge (MWOE) computation.
 */

/** A node in the enriched graph. The pdf maps message type names to
 *  their production probabilities (must sum to 1.0 within tolerance). */
final case class NodeDef(id: Int, pdf: Map[String, Double])

/** A directed edge in the enriched graph.
 *  - weight: numeric weight used by GHS (must be unique across all edges)
 *  - allowedTypes: message types permitted on this channel (edge label enforcement) */
final case class EdgeDef(
  from: Int,
  to: Int,
  weight: Double,
  allowedTypes: Set[String]
)

/** The fully enriched graph combining nodes and edges with all metadata.
 *  This is the central data structure passed from graph generation/loading
 *  into the Akka actor runtime. */
final case class EnrichedGraph(nodes: List[NodeDef], edges: List[EdgeDef]):

  /** Return all outgoing edges from a given node. */
  def neighborsOf(id: Int): List[EdgeDef] =
    edges.filter(_.from == id)

  /** Return the set of all node IDs. */
  def nodeIds: Set[Int] =
    nodes.map(_.id).toSet

  /** Validate all invariants required by the project:
   *  1. Node PDFs must sum to 1.0 (within tolerance)
   *  2. Edge weights must be unique (GHS requirement)
   *  3. Graph must be connected (algorithms require reachability)
   *  4. All edge endpoints must reference existing nodes
   *  Returns Left(error message) on failure, Right(()) on success. */
  def validate(): Either[String, Unit] =
    for
      _ <- validatePdfs()
      _ <- validateUniqueWeights()
      _ <- validateEndpoints()
      _ <- validateConnected()
    yield ()

  private def validatePdfs(): Either[String, Unit] =
    val badNodes = nodes.filter { n =>
      n.pdf.nonEmpty && math.abs(n.pdf.values.sum - 1.0) > 1e-6
    }
    if badNodes.nonEmpty then
      Left(s"PDF probabilities do not sum to 1.0 for nodes: ${badNodes.map(_.id).mkString(", ")}")
    else
      Right(())

  private def validateUniqueWeights(): Either[String, Unit] =
    val weights = edges.map(_.weight)
    if weights.size != weights.toSet.size then
      Left("GHS requires unique edge weights — duplicate weights found")
    else
      Right(())

  private def validateEndpoints(): Either[String, Unit] =
    val ids = nodeIds
    val badEdges = edges.filter(e => !ids.contains(e.from) || !ids.contains(e.to))
    if badEdges.nonEmpty then
      Left(s"Edges reference non-existent nodes: ${badEdges.map(e => s"${e.from}->${e.to}").mkString(", ")}")
    else
      Right(())

  /** BFS-based connectivity check treating the graph as undirected.
   *  Both algorithms require a connected graph to terminate correctly. */
  private def validateConnected(): Either[String, Unit] =
    if nodes.isEmpty then Right(())
    else
      // Build undirected adjacency from directed edges
      val adj = edges.foldLeft(Map.empty[Int, Set[Int]]) { (acc, e) =>
        acc
          .updated(e.from, acc.getOrElse(e.from, Set.empty) + e.to)
          .updated(e.to, acc.getOrElse(e.to, Set.empty) + e.from)
      }
      // BFS using tail-recursive helper with immutable collections
      val startId = nodes.head.id
      val allIds = nodeIds

      def bfs(queue: List[Int], visited: Set[Int]): Set[Int] =
        queue match
          case Nil => visited
          case head :: tail =>
            val newNeighbors = adj.getOrElse(head, Set.empty).diff(visited).toList
            bfs(tail ++ newNeighbors, visited ++ newNeighbors)

      val reachable = bfs(List(startId), Set(startId))
      if reachable == allIds then Right(())
      else Left(s"Graph is not connected. Unreachable nodes: ${(allIds -- reachable).mkString(", ")}")