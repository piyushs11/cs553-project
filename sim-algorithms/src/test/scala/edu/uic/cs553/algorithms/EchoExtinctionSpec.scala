package edu.uic.cs553.algorithms

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import edu.uic.cs553.core.{GraphGenerator, SimConfig}
import edu.uic.cs553.runtime.Simulator
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

  // Each test runs inside a fresh child guardian to avoid actor-name collisions.
  private def runTest(graphSeed: Long, nodeCount: Int, density: Double, testName: String): Int =
    val graph = GraphGenerator.generate(
      GraphGenerator.Config(nodeCount = nodeCount, edgeDensity = density, seed = graphSeed)
    )
    val config = SimConfig.loadDefault()

    val refs = graph.nodes.map { n =>
      val algo = new EchoExtinction(isInitiator = true)
      val props = edu.uic.cs553.runtime.NodeActor.props(n.id, List(algo), config.seed)
      n.id -> system.actorOf(props, s"$testName-node-${n.id}")
    }.toMap

    graph.nodes.foreach { n =>
      val outgoing = graph.neighborsOf(n.id)
      val neighborRefs = outgoing.flatMap(e => refs.get(e.to).map(e.to -> _)).toMap
      val allowed = outgoing.map(e => e.to -> e.allowedTypes).toMap
      val weights = outgoing.map(e => e.to -> e.weight).toMap
      refs(n.id) ! edu.uic.cs553.runtime.SimMessage.Init(
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
    Thread.sleep(2000)
    refs.values.foreach(system.stop)
    Thread.sleep(300)
    refs.size

  "EchoExtinction" should {

    "elect the highest-ID node when all nodes initiate on a small dense graph" in {
      val count = runTest(graphSeed = 42, nodeCount = 5, density = 0.6, testName = "test1")
      count shouldBe 5
    }

    "complete on a larger sparser graph with all nodes initiating" in {
      val count = runTest(graphSeed = 7, nodeCount = 8, density = 0.4, testName = "test2")
      count shouldBe 8
    }

    "work on a small triangle graph" in {
      val count = runTest(graphSeed = 100, nodeCount = 3, density = 1.0, testName = "test3")
      count shouldBe 3
    }
  }