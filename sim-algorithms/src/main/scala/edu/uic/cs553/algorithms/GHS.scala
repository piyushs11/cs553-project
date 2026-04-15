package edu.uic.cs553.algorithms

import edu.uic.cs553.algorithms.GHSMessages.*
import edu.uic.cs553.runtime.{DistributedAlgorithm, NodeContext}

/** Per-node GHS state. Edges are mutable for in-place state updates. */
final case class GHSState(
  level: Int = 0,
  phase: Phase = Phase.Sleeping,
  fragId: Double = 0.0,
  inBranch: Option[Int] = None,        // neighbor ID toward fragment core
  bestEdge: Option[Int] = None,        // neighbor ID of current MWOE candidate
  bestWeight: Double = Double.MaxValue,
  testEdge: Option[Int] = None,        // neighbor we're currently testing
  findCount: Int = 0,                  // pending Reports from subtree
  edges: List[GHSEdge] = Nil           // sorted by weight
)

/** Distributed minimum spanning tree via the Gallager-Humblet-Spira algorithm.
 *  Each fragment finds its minimum-weight outgoing edge and merges along it
 *  until one fragment spans the entire graph. */
class GHS extends DistributedAlgorithm:

  override def name: String = Name

  // Justified: actor-local state, single owner, atomically replaced per handler.
  private var state: GHSState = GHSState()
  private var initialized: Boolean = false
  private var mstComplete: Boolean = false

  override def onStart(ctx: NodeContext): Unit =
    // Build edge list from neighbor weights, sorted ascending.
    val edges = ctx.neighbors.keys.toList.map { nid =>
      new GHSEdge(neighborId = nid, weight = ctx.weightTo(nid))
    }.sortBy(_.weight)
    state = state.copy(edges = edges)
    initialized = true
    ctx.logInfo(s"GHS initialized with ${edges.size} edges")
    // Self-wake to begin the algorithm.
    handleWakeup(ctx)

  override def onMessage(ctx: NodeContext, from: Int, payload: Any): Unit =
    if !initialized then return
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

  //  Wakeup: claim minimum edge as Branch and send Connect on it ─
  private def handleWakeup(ctx: NodeContext): Unit =
    if state.phase != Phase.Sleeping then return
    state.edges.headOption.foreach { minEdge =>
      minEdge.state = EdgeState.Branch
      state = state.copy(level = 0, phase = Phase.Found, fragId = minEdge.weight)
      ctx.logInfo(s"Wakeup: Connect(0) on edge to node-${minEdge.neighborId} (w=${minEdge.weight})")
      ctx.send(minEdge.neighborId, Name, Connect(0))
    }

  //  Connect: either absorb the sender or merge with them ─
  private def handleConnect(ctx: NodeContext, from: Int, level: Int): Unit =
    if state.phase == Phase.Sleeping then handleWakeup(ctx)
    val edge = state.edges.find(_.neighborId == from).get

    if level < state.level then
      // Absorb the smaller fragment into ours.
      edge.state = EdgeState.Branch
      ctx.send(from, Name, Initiate(state.level, state.fragId, state.phase))
      ctx.logInfo(s"Absorbing fragment from node-$from (level $level into ${state.level})")
      if state.phase == Phase.Find then
        state = state.copy(findCount = state.findCount + 1)
    else if edge.state == EdgeState.Basic then
      // Same level but we don't know each other yet — defer (re-send to self).
      ctx.send(ctx.nodeId, Name, Connect(level))
    else
      // Same level on a Branch edge — merge: new fragment at level+1.
      val newLevel = state.level + 1
      val newFragId = edge.weight
      ctx.logInfo(s"Merging with node-$from at new level $newLevel (fragId=$newFragId)")
      ctx.send(from, Name, Initiate(newLevel, newFragId, Phase.Find))
      // We also start Find ourselves via the Initiate we'd receive — but
      // since we initiate the merge, transition directly here too.
      val branchNeighbors = state.edges.filter(_.state == EdgeState.Branch)
        .map(_.neighborId).filter(_ != from)
      branchNeighbors.foreach(n => ctx.send(n, Name, Initiate(newLevel, newFragId, Phase.Find)))
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

  //  Initiate: adopt new fragment ID and start finding MWOE ─
  private def handleInitiate(
    ctx: NodeContext, from: Int, level: Int, fragId: Double, phase: Phase
  ): Unit =
    val branchNeighbors = state.edges
      .filter(e => e.state == EdgeState.Branch && e.neighborId != from)
      .map(_.neighborId)

    branchNeighbors.foreach(n => ctx.send(n, Name, Initiate(level, fragId, phase)))

    state = state.copy(
      level = level,
      fragId = fragId,
      phase = phase,
      inBranch = Some(from),
      bestEdge = None,
      bestWeight = Double.MaxValue,
      findCount = if phase == Phase.Find then branchNeighbors.size else state.findCount
    )

    if phase == Phase.Find then
      findMWOE(ctx)
    else
      reportIfDone(ctx)

  //  Test: a neighbor wants to know if we share their fragment ─
  private def handleTest(ctx: NodeContext, from: Int, level: Int, fragId: Double): Unit =
    if state.phase == Phase.Sleeping then handleWakeup(ctx)
    if level > state.level then
      // Defer: requester is at higher level than us.
      ctx.send(ctx.nodeId, Name, Test(level, fragId))
    else if fragId != state.fragId then
      ctx.send(from, Name, Accept)
    else
      // Same fragment — Reject and mark edge Rejected on our side too.
      val edge = state.edges.find(_.neighborId == from).get
      if edge.state == EdgeState.Basic then edge.state = EdgeState.Rejected
      if state.testEdge.contains(from) then
        state = state.copy(testEdge = None)
        findMWOE(ctx)
      else
        ctx.send(from, Name, Reject)

  //  Accept: this edge is a valid outgoing candidate 
  private def handleAccept(ctx: NodeContext, from: Int): Unit =
    state = state.copy(testEdge = None)
    val edge = state.edges.find(_.neighborId == from).get
    if edge.weight < state.bestWeight then
      state = state.copy(bestEdge = Some(from), bestWeight = edge.weight)
    reportIfDone(ctx)

  //  Reject: not a candidate; mark and try next edge 
  private def handleReject(ctx: NodeContext, from: Int): Unit =
    val edge = state.edges.find(_.neighborId == from).get
    if edge.state == EdgeState.Basic then edge.state = EdgeState.Rejected
    state = state.copy(testEdge = None)
    findMWOE(ctx)

  //  Report: a subtree finished its search 
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
        // Defer until we transition to Found.
        ctx.send(ctx.nodeId, Name, Report(weight))
      else if weight > state.bestWeight then
        // We have a better edge — initiate the merge from our side.
        handleChangeRoot(ctx)
      else if weight == state.bestWeight && weight == Double.MaxValue then
        // Both sides exhausted — MST complete.
        if !mstComplete then
          mstComplete = true
          ctx.logInfo(s"*** MST COMPLETE *** fragment level=${state.level} fragId=${state.fragId}")

  //  ChangeRoot: forward Connect along the chosen MWOE 
  private def handleChangeRoot(ctx: NodeContext): Unit =
    state.bestEdge match
      case Some(target) =>
        val edge = state.edges.find(_.neighborId == target).get
        if edge.state == EdgeState.Branch then
          ctx.send(target, Name, ChangeRoot)
        else
          edge.state = EdgeState.Branch
          ctx.send(target, Name, Connect(state.level))
          ctx.logInfo(s"ChangeRoot: Connect(${state.level}) to node-$target (w=${edge.weight})")
      case None => ()

  //  findMWOE: send Test on the next Basic edge 
  private def findMWOE(ctx: NodeContext): Unit =
    state.edges.find(_.state == EdgeState.Basic) match
      case Some(edge) =>
        state = state.copy(testEdge = Some(edge.neighborId))
        ctx.send(edge.neighborId, Name, Test(state.level, state.fragId))
      case None =>
        state = state.copy(testEdge = None)
        reportIfDone(ctx)

  //  reportIfDone: if all subtrees reported and no test pending, send Report up 
  private def reportIfDone(ctx: NodeContext): Unit =
    if state.findCount == 0 && state.testEdge.isEmpty && state.phase == Phase.Find then
      state = state.copy(phase = Phase.Found)
      state.inBranch match
        case Some(parent) =>
          ctx.send(parent, Name, Report(state.bestWeight))
          ctx.logInfo(s"Report bestWeight=${state.bestWeight} to node-$parent")
        case None =>
          // We are the root of the fragment — initiate merge or finish.
          if state.bestWeight < Double.MaxValue then
            handleChangeRoot(ctx)
          else
            if !mstComplete then
              mstComplete = true
              ctx.logInfo(s"*** MST COMPLETE *** fragment level=${state.level}")

  /** Inspector: returns the list of MST edges from this node's perspective. */
  def getMSTEdges: List[(Int, Double)] =
    state.edges.filter(_.state == EdgeState.Branch).map(e => (e.neighborId, e.weight))

  def isComplete: Boolean = mstComplete