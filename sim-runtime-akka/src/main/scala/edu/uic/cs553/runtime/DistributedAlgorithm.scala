package edu.uic.cs553.runtime

/**
 * Plugin interface for distributed algorithms.
 *
 * Design rationale: Algorithms are pluggable modules that share the
 * same messaging substrate, logging, and instrumentation. Each algorithm
 * implements this trait and is registered with the NodeActor at creation.
 * The runtime dispatches incoming AlgorithmMsg messages to the appropriate
 * plugin based on the algorithmName field.
 */
trait DistributedAlgorithm:

  /** Unique name used for message routing and logging. */
  def name: String

  /** Called once when StartAlgorithms is received.
   *  Use this to initiate waves, send Connect messages, etc. */
  def onStart(ctx: NodeContext): Unit

  /** Called when an AlgorithmMsg with matching algorithmName arrives.
   *  The payload is the algorithm-specific message content. */
  def onMessage(ctx: NodeContext, from: Int, payload: Any): Unit

  /** Called on every timer tick.
   *  Override if the algorithm needs periodic actions. */
  def onTick(ctx: NodeContext): Unit = ()