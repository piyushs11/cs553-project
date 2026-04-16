package edu.uic.cs553.cli

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import edu.uic.cs553.algorithms.{EchoExtinction, GHS}
import edu.uic.cs553.core.{GraphGenerator, GraphLoader, SimConfig}
import edu.uic.cs553.runtime.{DistributedAlgorithm, SimMessage, Simulator}
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.io.Source

/** CLI entry point for running the distributed simulation.
 *  Usage:
 *    sbt "simCli/runMain edu.uic.cs553.cli.SimMain conf/exp-small.conf"
 *    sbt "simCli/runMain edu.uic.cs553.cli.SimMain conf/exp-medium.conf path/to/inject.txt"
 */
object SimMain:

  def main(args: Array[String]): Unit =
    if args.isEmpty then
      println("Usage: SimMain <config-file> [inject-file]")
      sys.exit(1)

    val configPath = args(0)
    val injectPath = if args.length > 1 then Some(args(1)) else None

    println(s"=== CS553 Distributed Simulation ===")
    println(s"Config: $configPath")
    injectPath.foreach(p => println(s"Injection file: $p"))
    println("=" * 40)

    val config = SimConfig.load(configPath)
    val graph = config.graphFile match
      case Some(path) =>
        println(s"Loading graph from $path")
        GraphLoader.fromFile(path)
      case None =>
        println(s"Generating graph: ${config.graphNodeCount} nodes, density=${config.graphEdgeDensity}")
        GraphGenerator.generate(config.toGraphGeneratorConfig)

    graph.validate() match
      case Left(err) => sys.error(s"Invalid graph: $err")
      case Right(_)  => println(s"Graph validated: ${graph.nodes.size} nodes, ${graph.edges.size / 2} undirected edges")

    val system = ActorSystem("cs553-sim")

    // Build per-node algorithm instances (each node needs its own).
    val algoInstances: Map[Int, List[DistributedAlgorithm]] = graph.nodes.map { n =>
      val algos = config.algorithms.flatMap {
        case "echo-extinction" => Some(new EchoExtinction(isInitiator = true))
        case "ghs"             => Some(new GHS())
        case other             =>
          println(s"Unknown algorithm: $other — skipping"); None
      }
      n.id -> algos
    }.toMap

    // Wire actors manually so we can inject per-node algorithm lists.
    val refs = graph.nodes.map { n =>
      val props = edu.uic.cs553.runtime.NodeActor.props(n.id, algoInstances(n.id), config.seed)
      n.id -> system.actorOf(props, s"node-${n.id}")
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
        timerEnabled = config.isTimerNode(n.id),
        tickEveryMs = config.tickEveryMs(n.id)
      )
    }

    println(s"Initialized ${refs.size} actors. Starting algorithms...")
    Thread.sleep(300) // let Init settle
    Simulator.startAlgorithms(refs)

    // File-driven injection: each line is "nodeId,kind,payload".
    injectPath.foreach { path =>
      val lines = Source.fromFile(path).getLines().toList
      println(s"Injecting ${lines.size} messages from $path")
      lines.foreach { line =>
        val parts = line.split(",", 3)
        if parts.length == 3 then
          val id = parts(0).trim.toInt
          refs.get(id).foreach(_ ! SimMessage.ExternalInput(parts(1).trim, parts(2).trim))
      }
    }

    val duration = config.runDurationSeconds.seconds
    println(s"Running for $duration...")
    Thread.sleep(duration.toMillis) // main thread, not inside any actor

    println("Shutting down...")
    system.terminate()
    Await.result(system.whenTerminated, 30.seconds)

    // Print final metrics report
    println()
    println(edu.uic.cs553.runtime.MetricsCollector.report())
    println("=== Simulation complete ===")