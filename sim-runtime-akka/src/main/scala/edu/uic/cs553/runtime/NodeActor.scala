package edu.uic.cs553.runtime

import akka.actor.{Actor, ActorRef, Props, Stash, Timers}
import scala.concurrent.duration.*
import scala.util.Random

object NodeActor:
  def props(id: Int, algorithms: List[DistributedAlgorithm], seed: Long): Props =
    Props(new NodeActor(id, algorithms, seed))

class NodeActor(
  val id: Int,
  val algorithms: List[DistributedAlgorithm],
  seed: Long
) extends Actor with Timers with Stash:

  import SimMessage.*

  // Justified: actor-local mutable state, standard Akka Classic pattern.
  // The RNG is seeded per-node for deterministic replay.
  private val rng = new Random(seed + id)

  override def receive: Receive = uninitialized

  private def uninitialized: Receive =
    case Init(neighbors, allowed, weights, pdf, timerEnabled, tickEveryMs) =>
      if timerEnabled then
        timers.startTimerAtFixedRate("tick", Tick, tickEveryMs.millis)
      val ctx = new NodeContext(id, neighbors, weights, self, context.system.log, allowed)
      unstashAll()
      context.become(running(ctx, neighbors, allowed, pdf))
      context.system.log.info(s"[node-$id] Initialized with ${neighbors.size} neighbors")

    case _ => stash()

  private def running(
    ctx: NodeContext,
    neighbors: Map[Int, ActorRef],
    allowed: Map[Int, Set[String]],
    pdf: Map[String, Double]
  ): Receive =

    case Tick =>
      val kind = samplePdf(pdf)
      sendToEligible(kind, s"tick-$id", neighbors, allowed)
      algorithms.foreach(_.onTick(ctx))

    case ExternalInput(kind, payload) =>
      sendToEligible(kind, payload, neighbors, allowed)

    case env: Envelope =>
      if allowed.getOrElse(env.from, Set.empty).contains(env.kind) then
        context.system.log.debug(s"[node-$id] Received ${env.kind} from node-${env.from}")
      else
        context.system.log.warning(s"[node-$id] DROPPED ${env.kind} from node-${env.from}")

    case AlgorithmMsg(algoName, from, payload) =>
      algorithms.filter(_.name == algoName).foreach(_.onMessage(ctx, from, payload))

    case StartAlgorithms =>
      algorithms.foreach(_.onStart(ctx))
      context.system.log.info(s"[node-$id] Algorithms started: ${algorithms.map(_.name).mkString(", ")}")

    case GetState(replyTo) =>
      replyTo ! StateResponse(id, Map(
        "nodeId" -> id,
        "neighbors" -> neighbors.keys.toList,
        "algorithms" -> algorithms.map(_.name)
      ))

  private def sendToEligible(
    kind: String, payload: String,
    neighbors: Map[Int, ActorRef],
    allowed: Map[Int, Set[String]]
  ): Unit =
    neighbors
      .filter((to, _) => allowed.getOrElse(to, Set.empty).contains(kind))
      .foreach((to, ref) => ref ! Envelope(from = id, kind = kind, payload = payload))

  private def samplePdf(pdf: Map[String, Double]): String =
    val r = rng.nextDouble()
    pdf.toList.sortBy(_._1)
      .scanLeft(("", 0.0))((acc, kv) => (kv._1, acc._2 + kv._2))
      .drop(1)
      .find(_._2 >= r)
      .map(_._1)
      .getOrElse(pdf.keys.head)