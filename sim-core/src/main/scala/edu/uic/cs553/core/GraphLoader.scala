package edu.uic.cs553.core

import io.circe.*
import io.circe.generic.auto.*
import io.circe.parser.*
import scala.io.Source

/**
 * Loads graph artifacts from JSON files.
 *
 * Supports two formats:
 * 1. Our native EnrichedGraph JSON (produced by GraphGenerator or serialized)
 * 2. A simplified adjacency format for quick testing
 *
 * Design rationale: Keeping the loader separate from the generator means
 * the runtime can accept graphs from any source — our generator, NetGameSim
 * export, or hand-crafted test fixtures.
 */
object GraphLoader:

  /** Load an EnrichedGraph from a JSON file.
   *  Fails fast with a descriptive error if parsing or validation fails. */
  def fromFile(path: String): EnrichedGraph =
    val raw = Source.fromFile(path).mkString
    fromJson(raw)

  /** Parse an EnrichedGraph from a JSON string. */
  def fromJson(json: String): EnrichedGraph =
    decode[EnrichedGraph](json) match
      case Right(graph) => graph
      case Left(error) => throw RuntimeException(s"Failed to parse graph JSON: ${error.getMessage}")

  /** Serialize an EnrichedGraph to a JSON string.
   *  Useful for saving generated graphs as artifacts. */
  def toJson(graph: EnrichedGraph): String =
    import io.circe.syntax.*
    graph.asJson.spaces2