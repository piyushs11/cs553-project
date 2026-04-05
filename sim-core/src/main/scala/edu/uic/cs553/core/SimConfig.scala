package edu.uic.cs553.core

import com.typesafe.config.{Config, ConfigFactory}
import scala.jdk.CollectionConverters.*

/**
 * Reads all simulation parameters from HOCON configuration files.
 *
 * Design rationale: Every parameter that affects system behavior is
 * externalized to configuration.
 * The config supports defaults via reference.conf and per-experiment
 * overrides via application.conf or CLI-specified files.
 */
final class SimConfig(raw: Config):

  // - Global seed for reproducibility -
  val seed: Long = raw.getLong("sim.seed")

  // - Graph generation parameters -
  val graphNodeCount: Int = raw.getInt("sim.graph.nodeCount")
  val graphEdgeDensity: Double = raw.getDouble("sim.graph.edgeDensity")
  val graphFile: Option[String] =
    if raw.hasPath("sim.graph.file") then Some(raw.getString("sim.graph.file"))
    else None

  // - Message types -
  val messageTypes: List[String] =
    raw.getStringList("sim.messages.types").asScala.toList

  // - Edge labeling -
  val defaultEdgeLabels: Set[String] =
    raw.getStringList("sim.edgeLabeling.default").asScala.toSet

  // - Traffic / PDF parameters -
  val tickIntervalMs: Long = raw.getLong("sim.traffic.tickIntervalMs")
  val distributionFamily: String = raw.getString("sim.traffic.distributionFamily")

  val defaultPdf: Map[String, Double] =
    raw.getConfigList("sim.traffic.defaultPdf").asScala.toList.map { c =>
      c.getString("msg") -> c.getDouble("p")
    }.toMap

  // - Initiators -
  val timerNodes: List[TimerNodeConfig] =
    raw.getConfigList("sim.initiators.timers").asScala.toList.map { c =>
      TimerNodeConfig(
        node = c.getInt("node"),
        tickEveryMs = c.getLong("tickEveryMs"),
        mode = c.getString("mode")
      )
    }

  val inputNodes: List[Int] =
    raw.getConfigList("sim.initiators.inputs").asScala.toList.map(_.getInt("node"))

  // - Algorithm selection -
  val algorithms: List[String] =
    raw.getStringList("sim.algorithms").asScala.toList

  // - Run duration -
  val runDurationSeconds: Int = raw.getInt("sim.runDurationSeconds")

  // - Helpers -
  def isTimerNode(id: Int): Boolean = timerNodes.exists(_.node == id)
  def tickEveryMs(id: Int): Long =
    timerNodes.find(_.node == id).map(_.tickEveryMs).getOrElse(tickIntervalMs)
  def isInputNode(id: Int): Boolean = inputNodes.contains(id)

  /** Build a GraphGenerator.Config from these settings. */
  def toGraphGeneratorConfig: GraphGenerator.Config =
    GraphGenerator.Config(
      nodeCount = graphNodeCount,
      edgeDensity = graphEdgeDensity,
      seed = seed,
      messageTypes = messageTypes,
      defaultLabels = defaultEdgeLabels,
      pdfDefault = defaultPdf
    )

/** Timer node configuration. */
final case class TimerNodeConfig(node: Int, tickEveryMs: Long, mode: String)

object SimConfig:
  /** Load config from a file path, falling back to reference.conf defaults. */
  def load(path: String): SimConfig =
    val fileConfig = ConfigFactory.parseFile(java.io.File(path))
    val resolved = fileConfig.withFallback(ConfigFactory.load()).resolve()
    SimConfig(resolved)

  /** Load from the default application.conf + reference.conf chain. */
  def loadDefault(): SimConfig =
    SimConfig(ConfigFactory.load())