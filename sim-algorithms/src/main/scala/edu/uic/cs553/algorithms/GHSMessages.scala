package edu.uic.cs553.algorithms

object GHSMessages:
  val Name = "ghs"

  // Node phases — drives the state machine.
  enum Phase:
    case Sleeping, Find, Found

  // Edge classification — every edge is in exactly one of these states.
  enum EdgeState:
    case Basic, Branch, Rejected

  // The seven GHS message types.
  sealed trait Msg

  /** Wakes a sleeping node — only ever sent to self at startup. */
  case object Wakeup extends Msg

  /** "I want to merge with your fragment via the edge you sent this on." */
  final case class Connect(level: Int) extends Msg

  /** "Start a new search phase with this fragment identity." */
  final case class Initiate(level: Int, fragId: Double, phase: Phase) extends Msg

  /** "Are you in a different fragment? Reply Accept or Reject." */
  final case class Test(level: Int, fragId: Double) extends Msg

  /** "Yes, I'm in a different fragment — this edge is a valid outgoing candidate." */
  case object Accept extends Msg

  /** "No, we're in the same fragment — mark this edge Rejected." */
  case object Reject extends Msg

  /** "My subtree's minimum outgoing weight is this." */
  final case class Report(bestWeight: Double) extends Msg

  /** "Forward a Connect along the chosen MWOE." */
  case object ChangeRoot extends Msg