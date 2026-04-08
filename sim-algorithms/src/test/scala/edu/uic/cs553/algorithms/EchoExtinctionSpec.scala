package edu.uic.cs553.algorithms

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import edu.uic.cs553.core.{GraphGenerator, SimConfig}
import edu.uic.cs553.runtime.{NodeActor, SimMessage, Simulator}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers

class EchoExtinctionSpec
    extends TestKit(ActorSystem("EchoExtinctionSpec", ConfigFactory.parseString(
      """akka.loglevel = "WARNING""""
    )))
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll:

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  /** Runs the algorithm and returns the per-node EchoExtinction instances
   *  so tests can inspect which leader each node settled on. */
  private def runAndCollect(
    graphSeed: Long,
    nodeCount: Int,
    density: Double,
    testName: String,
    allInitiators: Boolean
  ): List[EchoExtinction] =
    val graph = GraphGenerator.generate(
      GraphGenerator.Config(nodeCount = nodeCount, edgeDensity = density, seed = graphSeed)
    )
    val config = SimConfig.loadDefault()

    // Keep algorithm instances accessible for post-run inspection.
    val algoInstances: Map[Int, EchoExtinction] = graph.nodes.map { n =>
      val isInit = if allInitiators then true else n.id == 0
      n.id -> new EchoExtinction(isInitiator = isInit)
    }.toMap

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
    Thread.sleep(2500)
    refs.values.foreach(system.stop)
    Thread.sleep(300)

    algoInstances.values.toList

  "EchoExtinction" should {

    "elect the highest-ID node when all nodes initiate on a dense graph" in {
      val instances = runAndCollect(
        graphSeed = 42, nodeCount = 5, density = 0.8,
        testName = "correctness1", allInitiators = true
      )
      val leaders = instances.flatMap(_.getElectedLeader).toSet
      leaders should not be empty
      // All nodes that decided should agree on the maximum ID
      leaders shouldBe Set(4) // highest ID in a 5-node graph (IDs 0..4)
    }

    "elect the highest-ID node on a triangle" in {
      val instances = runAndCollect(
        graphSeed = 100, nodeCount = 3, density = 1.0,
        testName = "correctness2", allInitiators = true
      )
      val leaders = instances.flatMap(_.getElectedLeader).toSet
      leaders shouldBe Set(2) // highest ID in a 3-node graph (IDs 0..2)
    }

    "handle a single initiator correctly" in {
      val instances = runAndCollect(
        graphSeed = 7, nodeCount = 6, density = 0.5,
        testName = "correctness3", allInitiators = false
      )
      val leaders = instances.flatMap(_.getElectedLeader).toSet
      // Only node 0 initiates, so node 0 should become leader
      leaders shouldBe Set(0)
    }
  }