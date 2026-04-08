package edu.uic.cs553.algorithms

import edu.uic.cs553.runtime.{DistributedAlgorithm, NodeContext}
import scala.collection.mutable

object EchoExtinction:
  val Name = "echo-extinction"

  sealed trait Msg
  final case class Wave(initiatorId: Int) extends Msg
  final case class Echo(initiatorId: Int) extends Msg
  final case class Leader(leaderId: Int) extends Msg

/** Leader election via competing echo waves on a general connected graph.
 *  The initiator with the globally maximum ID wins. */
class EchoExtinction(isInitiator: Boolean) extends DistributedAlgorithm:
  import EchoExtinction.*

  override def name: String = Name

  // Actor-local mutable state — each node owns its own instance.
  private var bestWaveId: Int = -1
  private var parent: Option[Int] = None
  private var forwardedTo: Set[Int] = Set.empty
  private var echoesReceived: Set[Int] = Set.empty
  private var decided: Boolean = false
  private var electedLeader: Option[Int] = None

  override def onStart(ctx: NodeContext): Unit =
    if isInitiator then
      startWave(ctx)

  override def onMessage(ctx: NodeContext, from: Int, payload: Any): Unit =
    payload match
      case Wave(initId)  => handleWave(ctx, from, initId)
      case Echo(initId)  => handleEcho(ctx, from, initId)
      case Leader(lid)   => handleLeader(ctx, lid)
      case _             => ()

  private def startWave(ctx: NodeContext): Unit =
    bestWaveId = ctx.nodeId
    parent = None
    forwardedTo = ctx.neighborIds
    echoesReceived = Set.empty
    decided = false
    ctx.logInfo(s"Initiating wave with id=${ctx.nodeId}")
    ctx.broadcast(Name, Wave(ctx.nodeId))

    // Edge case: isolated initiator — no neighbors to echo back.
    if ctx.neighborCount == 0 then
      declareVictory(ctx)

  private def handleWave(ctx: NodeContext, from: Int, initId: Int): Unit =
    if initId > bestWaveId then
      // Higher wave arrives — reset and adopt it.
      bestWaveId = initId
      parent = Some(from)
      echoesReceived = Set.empty
      decided = false
      forwardedTo = ctx.neighborIds - from
      ctx.logInfo(s"Adopted wave id=$initId from node-$from")
      ctx.broadcastExcept(Name, Wave(initId), except = from)

      // Leaf case: no one to forward to, echo immediately.
      if forwardedTo.isEmpty then
        ctx.send(from, Name, Echo(initId))
    else if initId == bestWaveId && parent.isDefined then
      // Same wave, different path — send stale Echo so sender stops waiting.
      ctx.send(from, Name, Echo(initId))
    // else: lower wave, silently drop

  private def handleEcho(ctx: NodeContext, from: Int, initId: Int): Unit =
    if initId != bestWaveId then
      // Stale echo from an extinguished wave — discard.
      return

    echoesReceived = echoesReceived + from
    ctx.logDebug(s"Echo from node-$from (${echoesReceived.size}/${forwardedTo.size})")

    if echoesReceived == forwardedTo && !decided then
      decided = true
      parent match
        case Some(p) =>
          ctx.logInfo(s"All echoes received for wave $initId, echoing to parent node-$p")
          ctx.send(p, Name, Echo(initId))
        case None =>
          declareVictory(ctx)

  private def declareVictory(ctx: NodeContext): Unit =
    electedLeader = Some(ctx.nodeId)
    ctx.logInfo(s"*** ELECTED LEADER *** (wave $bestWaveId survived)")
    ctx.broadcast(Name, Leader(ctx.nodeId))

  private def handleLeader(ctx: NodeContext, leaderId: Int): Unit =
    electedLeader = Some(leaderId)
    ctx.logInfo(s"Acknowledged leader: node-$leaderId")

  def getElectedLeader: Option[Int] = electedLeader