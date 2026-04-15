package edu.uic.cs553.algorithms

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import edu.uic.cs553.core.{EnrichedGraph, GraphGenerator, SimConfig}
import edu.uic.cs553.runtime.{NodeActor, SimMessage, Simulator}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers

class GHSSpec
    extends TestKit(ActorSystem("GHSSpec", ConfigFactory.parseString(
      """akka.loglevel = "WARNING""""
    )))
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll:

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  /** Run Kruskal's algorithm sequentially to compute the expected MST weight. */
  private def kruskalMSTWeight(graph: EnrichedGraph): Double =
    // Deduplicate to undirected pairs.
    val undirected = graph.edges.map { e =>
      val key = if e.from < e.to then (e.from, e.to) else (e.to, e.from)
      (key, e.weight)
    }.distinctBy(_._1).sortBy(_._2)

    // Union-Find over node IDs.
    val parent = scala.collection.mutable.Map[Int, Int]()
    graph.nodes.foreach(n => parent(n.id) = n.id)

    def find(x: Int): Int =
      if parent(x) == x then x
      else { val r = find(parent(x)); parent(x) = r; r }

    def union(a: Int, b: Int): Boolean =
      val ra = find(a); val rb = find(b)
      if ra == rb then false else { parent(ra) = rb; true }

    undirected.foldLeft(0.0) { case (acc, ((a, b), w)) =>
      if union(a, b) then acc + w else acc
    }

  /** Spin up a GHS simulation and return the per-node algorithm instances. */
  private def runGHS(
    graphSeed: Long, nodeCount: Int, density: Double, testName: String
  ): (EnrichedGraph, List[GHS]) =
    val graph = GraphGenerator.generate(
      GraphGenerator.Config(nodeCount = nodeCount, edgeDensity = density, seed = graphSeed)
    )
    val config = SimConfig.loadDefault()

    val algoInstances: Map[Int, GHS] = graph.nodes.map { n => n.id -> new GHS() }.toMap

    val refs = graph.nodes.map { n =>
      val props = NodeActor.props(n.id, List(algoInstances(n.id)), config.seed)
      n.id -> system.actorOf(props, s"$testName-node-${n.id}")
    }.toMap

    graph.nodes.foreach { n =>
      val outgoing = graph.neighborsOf(n.id)
      val neighborRefs = outgoing.flatMap(e => refs.get(e.to).map(e.to -> _)).toMap
      val allowed = outgoing.map(e => e.to -> e.allowedTypes).toMap
      val weights = outgoing.map(e => e.to -> e.weight).toMap
      refs(n.id) ! SimMessage.Init(
        neighbors = neighborRefs,
        allowedOnEdge = allowed,
        edgeWeights = weights,
        pdf = n.pdf,
        timerEnabled = false,
        tickEveryMs = 1000L
      )
    }

    Thread.sleep(200)
    Simulator.startAlgorithms(refs)
    Thread.sleep(4000) // GHS needs more time than Echo
    refs.values.foreach(system.stop)
    Thread.sleep(300)

    (graph, algoInstances.values.toList)

  /** Sum all Branch-edge weights from all nodes; each edge counted twice
   *  (once from each endpoint), so divide by 2. */
  private def computeMSTWeight(instances: List[GHS]): Double =
    val totalDoubled = instances.flatMap(_.getMSTEdges).map(_._2).sum
    totalDoubled / 2.0

  /** Count distinct undirected MST edges. */
  private def countMSTEdges(instances: List[GHS]): Int =
    val pairs = instances.zipWithIndex.flatMap { (inst, idx) =>
      // We don't have nodeId on the instance directly; rely on the edge list.
      inst.getMSTEdges.map(_._1)
    }
    // Each undirected edge appears twice in branch lists, so count / 2.
    instances.flatMap(_.getMSTEdges).size / 2

  "GHS" should {

    "produce an MST with weight matching Kruskal's on a 4-node graph" in {
      val (graph, instances) = runGHS(graphSeed = 11, nodeCount = 4, density = 0.6, testName = "ghs1")
      val expected = kruskalMSTWeight(graph)
      val actual = computeMSTWeight(instances)
      println(s"4-node: GHS weight=$actual, Kruskal weight=$expected")
      actual shouldBe expected +- 0.001
    }

    "produce an MST with weight matching Kruskal's on a 6-node graph" in {
      val (graph, instances) = runGHS(graphSeed = 23, nodeCount = 6, density = 0.5, testName = "ghs2")
      val expected = kruskalMSTWeight(graph)
      val actual = computeMSTWeight(instances)
      println(s"6-node: GHS weight=$actual, Kruskal weight=$expected")
      actual shouldBe expected +- 0.001
    }

    "produce exactly N-1 MST edges on a connected graph" in {
      val (graph, instances) = runGHS(graphSeed = 5, nodeCount = 5, density = 0.7, testName = "ghs3")
      val edgeCount = countMSTEdges(instances)
      println(s"5-node: GHS produced $edgeCount MST edges (expected 4)")
      edgeCount shouldBe 4
    }
  }