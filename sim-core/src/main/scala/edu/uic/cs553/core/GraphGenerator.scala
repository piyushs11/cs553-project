package edu.uic.cs553.core

import scala.util.Random

/**
 * Configurable graph generator with seeded randomness for reproducibility.
 *
 * Design rationale: Rather than depending on NetGameSim at compile time, we provide a
 * standalone generator that produces graphs with the structural properties
 * our algorithms require: connectivity, distinct edge weights, and
 * configurable density. NetGameSim graphs can be loaded separately via
 * GraphLoader.
 *
 * The generator ensures:
 * - Connectivity via an initial spanning tree (random walk)
 * - Unique edge weights via sequential assignment with perturbation
 * - Configurable density by adding random edges after the spanning tree
 * - CONTROL message type allowed on every edge (algorithm requirement)
 */
object GraphGenerator:

  /** Configuration for graph generation.
   *  @param nodeCount     number of nodes (must be >= 2)
   *  @param edgeDensity   fraction of possible edges to include (0.0 to 1.0)
   *  @param seed          random seed for reproducibility
   *  @param messageTypes  all message types in the system
   *  @param defaultLabels default allowed message types per edge
   *  @param pdfDefault    default message production PDF for nodes */
  final case class Config(
    nodeCount: Int = 10,
    edgeDensity: Double = 0.4,
    seed: Long = 42L,
    messageTypes: List[String] = List("CONTROL", "PING", "GOSSIP", "WORK", "ACK"),
    defaultLabels: Set[String] = Set("CONTROL", "PING"),
    pdfDefault: Map[String, Double] = Map("PING" -> 0.50, "GOSSIP" -> 0.30, "WORK" -> 0.20)
  )

  /** Generate an enriched graph according to the given configuration. */
  def generate(config: Config): EnrichedGraph =
    val rng = new Random(config.seed)
    val n = config.nodeCount

    // Step 1: Create a random spanning tree to guarantee connectivity.
    // Shuffle node IDs and connect them in a chain.
    val ids = rng.shuffle((0 until n).toList)
    val treeEdges: List[(Int, Int)] = ids.sliding(2).toList.flatMap {
      case List(a, b) => List((a, b), (b, a))  // undirected: add both directions
      case _ => Nil
    }

    // Step 2: Add random extra edges to reach desired density.
    // Total possible directed edges = n * (n-1). We already have tree edges.
    val maxPossibleUndirected = n * (n - 1) / 2
    val targetUndirected = math.max(n - 1, (maxPossibleUndirected * config.edgeDensity).toInt)
    val existingPairs: Set[(Int, Int)] = treeEdges.map { (a, b) =>
      if a < b then (a, b) else (b, a)
    }.toSet

    val allPossiblePairs = (0 until n).flatMap { i =>
      ((i + 1) until n).map(j => (i, j))
    }.filterNot(existingPairs.contains)

    val extraPairs = rng.shuffle(allPossiblePairs.toList)
      .take(targetUndirected - existingPairs.size)

    val extraEdges = extraPairs.flatMap { (a, b) => List((a, b), (b, a)) }
    val allDirectedEdges = treeEdges ++ extraEdges

    // Step 3: Assign unique weights.
    // Using index-based weights with small perturbation guarantees uniqueness.
    val undirectedPairs = allDirectedEdges
      .map { (a, b) => if a < b then (a, b) else (b, a) }
      .distinct

    val weightMap: Map[(Int, Int), Double] = undirectedPairs.zipWithIndex.map {
      case ((a, b), idx) => (a, b) -> (idx + 1).toDouble
    }.toMap

    // Step 4: Build EdgeDefs with labels. CONTROL is always included.
    val edgeDefs = allDirectedEdges.map { (from, to) =>
      val key = if from < to then (from, to) else (to, from)
      val weight = weightMap(key)
      EdgeDef(
        from = from,
        to = to,
        weight = weight,
        allowedTypes = config.defaultLabels + "CONTROL"  // CONTROL always allowed
      )
    }

    // Step 5: Build NodeDefs with default PDFs.
    val nodeDefs = (0 until n).toList.map { id =>
      NodeDef(id = id, pdf = config.pdfDefault)
    }

    EnrichedGraph(nodes = nodeDefs, edges = edgeDefs)