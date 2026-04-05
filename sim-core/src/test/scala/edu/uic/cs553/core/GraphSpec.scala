package edu.uic.cs553.core

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for the core graph data model, generator, and validation.
 * These verify the invariants required by both assigned algorithms:
 * - Echo with Extinction requires a connected graph
 * - GHS requires unique edge weights and connectivity
 */
class GraphSpec extends AnyWordSpec with Matchers:

  "EnrichedGraph validation" should {

    "accept a valid graph with correct PDFs and unique weights" in {
      val graph = GraphGenerator.generate(GraphGenerator.Config(nodeCount = 5, seed = 42))
      graph.validate() shouldBe Right(())
    }

    "reject a graph where PDF does not sum to 1.0" in {
      val badNode = NodeDef(0, Map("PING" -> 0.3, "GOSSIP" -> 0.3)) // sums to 0.6
      val goodNode = NodeDef(1, Map("PING" -> 0.5, "GOSSIP" -> 0.5))
      val graph = EnrichedGraph(
        nodes = List(badNode, goodNode),
        edges = List(EdgeDef(0, 1, 1.0, Set("CONTROL")), EdgeDef(1, 0, 2.0, Set("CONTROL")))
      )
      val result = graph.validate()
      result.isLeft shouldBe true
      result.left.getOrElse("") should include("sum to 1.0")
    }

    "reject a graph with duplicate edge weights" in {
      val nodes = List(
        NodeDef(0, Map("PING" -> 1.0)),
        NodeDef(1, Map("PING" -> 1.0)),
        NodeDef(2, Map("PING" -> 1.0))
      )
      // Edges (0→1) and (1→2) are different undirected pairs but share weight 5.0
      val edges = List(
        EdgeDef(0, 1, 5.0, Set("CONTROL")),
        EdgeDef(1, 0, 5.0, Set("CONTROL")),
        EdgeDef(1, 2, 5.0, Set("CONTROL")),  // duplicate weight on different pair
        EdgeDef(2, 1, 5.0, Set("CONTROL"))
      )
      val graph = EnrichedGraph(nodes, edges)
      val result = graph.validate()
      result.isLeft shouldBe true
      result.left.getOrElse("") should include("unique")
    }

    "reject a disconnected graph" in {
      val nodes = List(
        NodeDef(0, Map("PING" -> 1.0)),
        NodeDef(1, Map("PING" -> 1.0)),
        NodeDef(2, Map("PING" -> 1.0))  // isolated node
      )
      val edges = List(
        EdgeDef(0, 1, 1.0, Set("CONTROL")),
        EdgeDef(1, 0, 2.0, Set("CONTROL"))
      )
      val graph = EnrichedGraph(nodes, edges)
      val result = graph.validate()
      result.isLeft shouldBe true
      result.left.getOrElse("") should include("not connected")
    }
  }

  "GraphGenerator" should {

    "produce a connected graph with unique weights" in {
      val config = GraphGenerator.Config(nodeCount = 20, edgeDensity = 0.3, seed = 99)
      val graph = GraphGenerator.generate(config)

      graph.nodes.size shouldBe 20
      graph.validate() shouldBe Right(())
    }

    "produce deterministic output for the same seed" in {
      val config = GraphGenerator.Config(nodeCount = 10, seed = 123)
      val graph1 = GraphGenerator.generate(config)
      val graph2 = GraphGenerator.generate(config)

      graph1.edges.map(_.weight) shouldBe graph2.edges.map(_.weight)
      graph1.nodes.map(_.id) shouldBe graph2.nodes.map(_.id)
    }

    "include CONTROL in every edge's allowed types" in {
      val graph = GraphGenerator.generate(GraphGenerator.Config(nodeCount = 8, seed = 7))
      graph.edges.foreach { edge =>
        edge.allowedTypes should contain("CONTROL")
      }
    }
  }

  "GraphLoader" should {

    "round-trip a graph through JSON serialization" in {
      val original = GraphGenerator.generate(GraphGenerator.Config(nodeCount = 5, seed = 55))
      val json = GraphLoader.toJson(original)
      val loaded = GraphLoader.fromJson(json)

      loaded.nodes.size shouldBe original.nodes.size
      loaded.edges.size shouldBe original.edges.size
      loaded.validate() shouldBe Right(())
    }
  }