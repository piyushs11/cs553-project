package edu.uic.cs553.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*

/** Thread-safe metrics collector used by all NodeActors.
 *  Substitutes for Cinnamon (which requires a Lightbend commercial license).
 *  Tracks message counts by type, dropped messages, and algorithm events. */
object MetricsCollector:

  // Keyed counters — ConcurrentHashMap + AtomicLong is safe across all actor threads.
  private val counters: ConcurrentHashMap[String, AtomicLong] = new ConcurrentHashMap()

  def increment(key: String): Unit =
    counters.computeIfAbsent(key, _ => new AtomicLong(0)).incrementAndGet()

  def add(key: String, value: Long): Unit =
    counters.computeIfAbsent(key, _ => new AtomicLong(0)).addAndGet(value)

  def get(key: String): Long =
    Option(counters.get(key)).map(_.get()).getOrElse(0L)

  def snapshot(): Map[String, Long] =
    counters.asScala.map((k, v) => k -> v.get()).toMap

  def reset(): Unit = counters.clear()

  /** Pretty-print all metrics, grouped by prefix. */
  def report(): String =
    val all = snapshot().toList.sortBy(_._1)
    if all.isEmpty then "No metrics collected."
    else
      val header = "=" * 50 + "\n  METRICS REPORT\n" + "=" * 50
      val body = all.map((k, v) => f"  $k%-40s $v%8d").mkString("\n")
      s"$header\n$body\n${"=" * 50}"