package edu.uic.cs553.algorithms

import edu.uic.cs553.algorithms.GHSMessages.*
import edu.uic.cs553.runtime.{DistributedAlgorithm, NodeContext}

final case class GHSState(
  level: Int = 0,
  phase: Phase = Phase.Sleeping,
  fragId: Double = 0.0,
  inBranch: Option[Int] = None,
  bestEdge: Option[Int] = None,
  bestWeight: Double = Double.MaxValue,
  testEdge: Option[Int] = None,
  findCount: Int = 0,
  edges: List[GHSEdge] = Nil
)

class GHS extends DistributedAlgorithm:
  // Wrapper that preserves the original sender across self-deferrals.
  private case class Deferred(originalFrom: Int, payload: Any)

  override def name: String = Name

  // Justified: actor-local mutable state, single owner.
  private var state: GHSState = GHSState()
  private var initialized: Boolean = false
  private var mstComplete: Boolean = false

  override def onStart(ctx: NodeContext): Unit =
    val edges = ctx.neighbors.keys.toList.map { nid =>
      new GHSEdge(neighborId = nid, weight = ctx.weightTo(nid))
    }.sortBy(_.weight)
    state = state.copy(edges = edges)
    initialized = true
    ctx.logInfo(s"GHS init with ${edges.size} edges")
    handleWakeup(ctx)

  override def onMessage(ctx: NodeContext, from: Int, payload: Any): Unit =
    if !initialized then return
    payload match
      case Deferred(orig, inner) => dispatch(ctx, orig, inner)
      case other                 => dispatch(ctx, from, other)

  private def dispatch(ctx: NodeContext, from: Int, payload: Any): Unit =
    payload match
      case Wakeup              => handleWakeup(ctx)
      case Connect(lvl)        => handleConnect(ctx, from, lvl)
      case Initiate(l, f, p)   => handleInitiate(ctx, from, l, f, p)
      case Test(l, f)          => handleTest(ctx, from, l, f)
      case Accept              => handleAccept(ctx, from)
      case Reject              => handleReject(ctx, from)
      case Report(w)           => handleReport(ctx, from, w)
      case ChangeRoot          => handleChangeRoot(ctx)
      case _                   => ()

  // Defer a message to ourselves while preserving the original sender.
  private def defer(ctx: NodeContext, originalFrom: Int, payload: Any): Unit =
    ctx.sendSelf(Name, Deferred(originalFrom, payload))

  // ── Wakeup ──
  private def handleWakeup(ctx: NodeContext): Unit =
    if state.phase != Phase.Sleeping then return
    state.edges.headOption match
      case Some(minEdge) =>
        minEdge.state = EdgeState.Branch
        state = state.copy(level = 0, phase = Phase.Found, fragId = minEdge.weight)
        ctx.logInfo(s"Wakeup: Connect(0) to node-${minEdge.neighborId} w=${minEdge.weight}")
        ctx.send(minEdge.neighborId, Name, Connect(0))
      case None =>
        // Isolated node — already done
        if !mstComplete then
          mstComplete = true
          ctx.logInfo("MST complete (isolated node)")

  // ── Connect ──
  private def handleConnect(ctx: NodeContext, from: Int, level: Int): Unit =
    if state.phase == Phase.Sleeping then handleWakeup(ctx)

    state.edges.find(_.neighborId == from) match
      case None =>
        ctx.logWarning(s"Connect from unknown neighbor $from — dropping")
      case Some(edge) =>
        if level < state.level then
          // Absorb the smaller fragment.
          edge.state = EdgeState.Branch
          ctx.send(from, Name, Initiate(state.level, state.fragId, state.phase))
          ctx.logInfo(s"Absorbed node-$from (lvl $level → $state.level)")
          if state.phase == Phase.Find then
            state = state.copy(findCount = state.findCount + 1)
        else if edge.state == EdgeState.Basic then
          // Need to test this edge first; defer.
          defer(ctx, from, Connect(level))
        else
          // Same level + Branch edge => merge to level+1, this edge is the new core.
          val newLevel = state.level + 1
          val newFragId = edge.weight
          ctx.logInfo(s"Merge with node-$from at level $newLevel fragId=$newFragId")
          // Send Initiate(Find) on every Branch edge (including the new core).
          val branchNeighbors = state.edges.filter(_.state == EdgeState.Branch).map(_.neighborId)
          branchNeighbors.foreach(n =>
            ctx.send(n, Name, Initiate(newLevel, newFragId, Phase.Find))
          )
          state = state.copy(
            level = newLevel,
            fragId = newFragId,
            phase = Phase.Find,
            inBranch = None,
            bestEdge = None,
            bestWeight = Double.MaxValue,
            findCount = branchNeighbors.size
          )
          findMWOE(ctx)

  // ── Initiate ──
  private def handleInitiate(
    ctx: NodeContext, from: Int, level: Int, fragId: Double, phase: Phase
  ): Unit =
    val branchNeighbors = state.edges
      .filter(e => e.state == EdgeState.Branch && e.neighborId != from)
      .map(_.neighborId)

    branchNeighbors.foreach(n =>
      ctx.send(n, Name, Initiate(level, fragId, phase))
    )

    state = state.copy(
      level = level,
      fragId = fragId,
      phase = phase,
      inBranch = Some(from),
      bestEdge = None,
      bestWeight = Double.MaxValue,
      findCount = if phase == Phase.Find then branchNeighbors.size else state.findCount
    )

    if phase == Phase.Find then findMWOE(ctx)
    else reportIfDone(ctx)

  // ── Test ──
  private def handleTest(ctx: NodeContext, from: Int, level: Int, fragId: Double): Unit =
    if state.phase == Phase.Sleeping then handleWakeup(ctx)
    if level > state.level then
      defer(ctx, from, Test(level, fragId))
    else if fragId != state.fragId then
      ctx.send(from, Name, Accept)
    else
      state.edges.find(_.neighborId == from).foreach { edge =>
        if edge.state == EdgeState.Basic then edge.state = EdgeState.Rejected
      }
      if state.testEdge.contains(from) then
        state = state.copy(testEdge = None)
        findMWOE(ctx)
      else
        ctx.send(from, Name, Reject)

  // ── Accept ──
  private def handleAccept(ctx: NodeContext, from: Int): Unit =
    state = state.copy(testEdge = None)
    state.edges.find(_.neighborId == from).foreach { edge =>
      if edge.weight < state.bestWeight then
        state = state.copy(bestEdge = Some(from), bestWeight = edge.weight)
    }
    reportIfDone(ctx)

  // ── Reject ──
  private def handleReject(ctx: NodeContext, from: Int): Unit =
    state.edges.find(_.neighborId == from).foreach { edge =>
      if edge.state == EdgeState.Basic then edge.state = EdgeState.Rejected
    }
    state = state.copy(testEdge = None)
    findMWOE(ctx)

  // ── Report ──
  private def handleReport(ctx: NodeContext, from: Int, weight: Double): Unit =
    if !state.inBranch.contains(from) then
      // Report from a child subtree.
      if weight < state.bestWeight then
        state = state.copy(bestEdge = Some(from), bestWeight = weight)
      state = state.copy(findCount = state.findCount - 1)
      reportIfDone(ctx)
    else
      // Report from the parent direction.
      if state.phase == Phase.Find then
        defer(ctx, from, Report(weight))
      else if weight > state.bestWeight then
        handleChangeRoot(ctx)
      else if weight == Double.MaxValue && state.bestWeight == Double.MaxValue then
        if !mstComplete then
          mstComplete = true
          ctx.logInfo(s"*** MST COMPLETE *** level=${state.level} fragId=${state.fragId}")

  // ── ChangeRoot ──
  private def handleChangeRoot(ctx: NodeContext): Unit =
    state.bestEdge match
      case Some(target) =>
        state.edges.find(_.neighborId == target).foreach { edge =>
          if edge.state == EdgeState.Branch then
            ctx.send(target, Name, ChangeRoot)
          else
            edge.state = EdgeState.Branch
            ctx.send(target, Name, Connect(state.level))
            ctx.logInfo(s"ChangeRoot: Connect(${state.level}) to node-$target w=${edge.weight}")
        }
      case None => ()

  // ── findMWOE ──
  private def findMWOE(ctx: NodeContext): Unit =
    state.edges.find(_.state == EdgeState.Basic) match
      case Some(edge) =>
        state = state.copy(testEdge = Some(edge.neighborId))
        ctx.send(edge.neighborId, Name, Test(state.level, state.fragId))
      case None =>
        state = state.copy(testEdge = None)
        reportIfDone(ctx)

  // ── reportIfDone ──
  private def reportIfDone(ctx: NodeContext): Unit =
    if state.findCount == 0 && state.testEdge.isEmpty && state.phase == Phase.Find then
      state = state.copy(phase = Phase.Found)
      state.inBranch match
        case Some(parent) =>
          ctx.send(parent, Name, Report(state.bestWeight))
          ctx.logInfo(s"Report w=${state.bestWeight} to node-$parent")
        case None =>
          if state.bestWeight < Double.MaxValue then
            handleChangeRoot(ctx)
          else if !mstComplete then
            mstComplete = true
            ctx.logInfo(s"*** MST COMPLETE *** level=${state.level}")

  def getMSTEdges: List[(Int, Double)] =
    state.edges.filter(_.state == EdgeState.Branch).map(e => (e.neighborId, e.weight))

  def isComplete: Boolean = mstComplete