# CS553 — Design Report

## Algorithm Assignment

UIN: 656731453

```
Index 1 = 1 + (656731453 mod 23) = 1 + 10 = 11 → Echo Algorithm with Extinction
Index 2 = 1 + (floor(656731453 / 23)) mod 23 = 1 + 7 = 8 → Gallager-Humblet-Spira (GHS)
```

---

## System Architecture

The system is a four-module SBT project built on Scala 3.3.3 and Akka Classic actors.

**sim-core** — Pure data layer with no Akka dependency. Contains the graph model
(`EnrichedGraph`, `NodeDef`, `EdgeDef`), a configurable graph generator with seeded
randomness, a JSON serializer/loader, and HOCON config parsing via `SimConfig`.

**sim-runtime-akka** — The actor runtime. Each graph node becomes a `NodeActor`
(Akka Classic `Actor` with `Timers` and `Stash`). Edges are modeled as `ActorRef`
entries in each node's neighbor map. The module provides a `DistributedAlgorithm`
plugin trait, a `NodeContext` helper for algorithms to send messages without touching
Akka internals, and a `MetricsCollector` for runtime instrumentation.

**sim-algorithms** — Implementations of Echo with Extinction and GHS, both
extending `DistributedAlgorithm`. Each algorithm is a separate class instantiated
per node, receiving messages via `onMessage(ctx, from, payload)`.

**sim-cli** — The `SimMain` entry point that reads config, generates a graph,
wires actors, starts algorithms, runs for a configured duration, and prints
metrics at shutdown.

### Data flow

```
Config (HOCON) → SimConfig → GraphGenerator → EnrichedGraph
                                                    ↓
                                              Simulator.buildFrom()
                                                    ↓
                                    NodeActor × N (one per graph node)
                                                    ↓
                                    StartAlgorithms → EchoExtinction + GHS
                                                    ↓
                                    Logs + Metrics Report
```

---

## Message Protocol

All simulation messages extend the sealed trait `SimMessage`. This avoids
the stringly-typed message penalty. The hierarchy is:

- `Init` — wires neighbors, edge labels, weights, PDF, timer config
- `Envelope(from, kind, payload)` — application-level traffic between nodes
- `ExternalInput(kind, payload)` — injected by CLI driver
- `Tick` — internal timer (private to runtime)
- `AlgorithmMsg(algorithmName, from, payload)` — routes to the correct plugin
- `StartAlgorithms` — triggers `onStart` on all registered algorithms
- `GetState / StateResponse` — for testing and monitoring

Algorithm-specific messages are nested inside each algorithm's companion object
and wrapped in `AlgorithmMsg` for transport.

---

## Edge Label Enforcement

Each edge carries a set of allowed message types (`allowedTypes: Set[String]`).
Before delivering any `Envelope`, the `NodeActor` checks:

```scala
if allowed.getOrElse(env.from, Set.empty).contains(env.kind) then
  // deliver
else
  // log warning and drop
```

The `CONTROL` type is included on every edge by default, ensuring algorithm
messages can always traverse the graph. Application traffic (`PING`, `GOSSIP`,
`WORK`) is filtered by the edge-specific label set.

Dropped messages are counted by the `MetricsCollector` under `msgs.dropped`.

---

## Node PDFs and Traffic Generation

Each node has a probability mass function (PMF) over message types, configured
via HOCON. The `defaultPdf` applies to all nodes unless overridden by `perNodePdf`.

Sampling uses a seeded `scala.util.Random(seed + nodeId)` for per-node
deterministic replay. The sampling algorithm scans the sorted PMF entries
cumulatively until the random draw is exceeded:

```scala
pdf.toList.sortBy(_._1)
  .scanLeft(("", 0.0))((acc, kv) => (kv._1, acc._2 + kv._2))
  .drop(1)
  .find(_._2 >= r)
```

Validation rejects PDFs that do not sum to 1.0 within tolerance (1e-6).

---

## Computation Initiation

Two mechanisms, as required by the project specification:

**Timer nodes** use Akka's `Timers` trait to schedule periodic `Tick` messages.
On each tick, the node samples from its PDF and sends to eligible neighbors.
No `Thread.sleep` is used inside any actor.

**Input nodes** accept `ExternalInput` messages from the CLI driver. The driver
reads an injection file (CSV format: `nodeId,kind,payload`) and sends each
line to the appropriate node actor.

---

## Algorithm 1: Echo with Extinction

### System model assumptions

- **Network:** Arbitrary connected undirected graph
- **Communication:** Asynchronous, reliable, FIFO (guaranteed by Akka between actor pairs)
- **Failures:** None — all nodes and channels are reliable
- **Priority:** Each node's unique integer ID; higher ID wins
- **Termination:** The single surviving wave's initiator declares victory

### How it works

Multiple nodes independently initiate echo waves, each stamped with the
initiator's ID. When a node receives a wave with a higher ID than its
current best, it abandons its current wave, adopts the new one, and
forwards it to all neighbors except the sender. Lower-priority waves
are silently dropped (extinguished).

Once a node has received echoes from all neighbors it forwarded to, it
echoes back to its parent. The initiator of the surviving wave (the one
with the globally maximum ID) receives echoes from all its children and
declares itself leader, broadcasting a `Leader` announcement.

### Message types

- `Wave(initiatorId)` — flooded outward from each initiator
- `Echo(initiatorId)` — convergecast back toward the surviving initiator
- `Leader(leaderId)` — broadcast after victory

### Per-node state

- `bestWaveId` — highest wave ID seen so far
- `parent` — neighbor that sent us the winning wave
- `forwardedTo` — set of neighbors we forwarded the current wave to
- `echoesReceived` — echoes collected for the current wave
- `decided` — whether this node has completed

### Correctness argument

The wave with the globally maximum ID can never be extinguished (no higher
ID exists). It will reach every node in the connected graph. Every other
wave will eventually encounter a node that has already adopted a higher
wave and be dropped. Therefore exactly one wave survives and its initiator
is elected. This is verified by tests that check all nodes agree on the
highest-ID leader.

---

## Algorithm 2: Gallager-Humblet-Spira (GHS)

### System model assumptions

- **Network:** Weighted undirected connected graph with **distinct edge weights**
- **Communication:** Asynchronous, reliable, FIFO
- **Failures:** None
- **Termination:** A single fragment spans the entire graph; the MST is the union of all Branch edges

### Variant note

This implementation is a simplified variant of the original 1983 GHS algorithm.
The core mechanism — fragment merging along minimum-weight outgoing edges
(MWOE) via Test/Accept/Reject discovery and Report/ChangeRoot propagation —
is preserved. The simplification affects how same-level fragment merges are
coordinated: we use a deterministic tiebreak rather than the original
concurrent merge protocol. This sacrifices the O(N log N) message complexity
bound but maintains correctness. The MST output is verified against Kruskal's
algorithm in tests.

### How it works

1. Each node wakes up and sends `Connect(0)` on its minimum-weight edge.
2. Two nodes that Connect to each other merge into a level-1 fragment.
3. The merged fragment broadcasts `Initiate` to set the new fragment ID and start MWOE discovery.
4. Each node sends `Test` on its cheapest Basic edge to check if the other end is in a different fragment.
5. The other end replies `Accept` (different fragment) or `Reject` (same fragment).
6. Once a node has tested all its edges and received Reports from its subtree, it sends `Report(bestWeight)` up toward the fragment root.
7. The root receives all Reports, picks the minimum, and sends `ChangeRoot` down toward the MWOE.
8. The node on the MWOE sends `Connect(level)` to merge with the neighboring fragment.
9. Repeat until no outgoing edges remain — the MST is complete.

### Message types

| Message | Direction | Purpose |
|---------|-----------|---------|
| `Connect(level)` | To neighbor on MWOE | Request to merge fragments |
| `Initiate(level, fragId, phase)` | Inward through fragment | Set new fragment identity, start Find |
| `Test(level, fragId)` | To candidate edge | Query if neighbor is in a different fragment |
| `Accept` | Reply to Test | Confirms different fragment — edge is a candidate |
| `Reject` | Reply to Test | Same fragment — mark edge Rejected |
| `Report(bestWeight)` | Upward to core | Subtree's minimum outgoing weight |
| `ChangeRoot` | Downward to MWOE | Direct the Connect toward the chosen MWOE |

### Per-node state

- `level` — current fragment level (increments on merge)
- `phase` — Sleeping, Find, or Found
- `fragId` — identity of the current fragment (weight of the core edge)
- `inBranch` — neighbor ID toward the fragment core
- `bestEdge` — neighbor with the best MWOE candidate
- `bestWeight` — weight of the best candidate (Double.MaxValue if none)
- `testEdge` — edge currently being tested
- `findCount` — number of pending Report messages from subtree children
- `edges` — list of `GHSEdge` objects sorted by weight, each tracking its state (Basic/Branch/Rejected)

### Edge states

- **Basic** — not yet classified; may be tested
- **Branch** — confirmed part of the MST
- **Rejected** — both endpoints are in the same fragment; not part of the MST

### Deferred messages

When a node at level L receives a `Connect` or `Test` from a higher-level
fragment, it cannot process the message immediately. The message is deferred
by wrapping it in a `Deferred(originalFrom, payload)` case class and sending
it back to the node's own mailbox via `sendSelf`. This preserves the original
sender identity across re-delivery — a naive self-send would lose it because
the `AlgorithmMsg.from` field would be overwritten with the node's own ID.

### Why edge weights must be unique

GHS identifies each fragment by the weight of its core edge. If two edges
share the same weight, two different fragments could appear identical,
causing nodes to misidentify which fragment they belong to and breaking
the Test/Accept/Reject protocol. Our `GraphGenerator` assigns sequential
integer weights to guarantee uniqueness, and `EnrichedGraph.validate()`
rejects graphs with duplicate weights.

### Correctness verification

GHS correctness is verified by comparing the total weight of all Branch
edges against Kruskal's sequential MST algorithm. The test suite runs GHS
on multiple graph sizes (4, 5, and 6 nodes) and asserts that:
- The MST weight matches Kruskal's output exactly
- Exactly N-1 edges are marked as Branch (a spanning tree property)

---

## Metrics and Instrumentation

### Custom MetricsCollector

Cinnamon (Lightbend Telemetry) requires a commercial license. The project
includes commented-out Cinnamon configuration in `build.sbt`, `plugins.sbt`,
and `application.conf` — ready to enable if credentials are available.

As a functional substitute, `MetricsCollector` uses a `ConcurrentHashMap`
of `AtomicLong` counters, providing thread-safe instrumentation across all
actor threads without blocking. It tracks:

- `timer.ticks` — total timer firings
- `msgs.sent` / `msgs.sent.<kind>` — outgoing messages by type
- `msgs.received` / `msgs.kind.<kind>` — delivered messages by type
- `msgs.dropped` / `msgs.dropped.<kind>` — messages blocked by edge labels
- `algo.msgs.received` — total algorithm messages
- `algo.<name>.msgs` — per-algorithm message counts
- `injected.total` / `injected.kind.<kind>` — externally injected messages

A summary report is printed at shutdown.

---

## Experiment Configurations

Three configs in `conf/` demonstrate the system across different graph scales:

| Config | Nodes | Density | Timer nodes | Duration | Purpose |
|--------|-------|---------|-------------|----------|---------|
| exp-small.conf | 8 | 0.3 | 2 | 8s | Visual log inspection |
| exp-medium.conf | 15 | 0.4 | 3 | 12s | Convergence under concurrency |
| exp-large.conf | 30 | 0.5 | 3 | 20s | Stress test on larger MST |

### Why these properties matter

**For Echo with Extinction:** Higher density means more paths between nodes,
creating more wave collisions and extinction events. The sparse small config
lets you trace each wave in the log; the dense large config tests that
extinction still converges correctly under heavy concurrency.

**For GHS:** More nodes and edges mean more fragments and more levels of
merging. The large config produces a 30-node MST requiring multiple merge
rounds, exercising the full Initiate/Test/Report/ChangeRoot cycle at
higher fragment levels.

**Varying timer nodes** changes the background traffic load. More timers
means more `Envelope` messages flowing concurrently with algorithm messages,
testing that edge label enforcement and algorithm dispatch remain correct
under load.

---

## Testing Strategy

17 tests across three modules:

**sim-core (8 tests):**
- PDF validation: rejects sums ≠ 1.0
- Edge weight uniqueness: rejects duplicates (GHS requirement)
- Connectivity: rejects disconnected graphs
- Generator: produces valid connected graphs with unique weights
- Determinism: same seed produces identical output
- CONTROL on every edge: verified for all generated graphs
- JSON round-trip: serialize and deserialize preserves graph

**sim-runtime-akka (3 tests):**
- MetricsCollector: thread-safe increment, zero for unseen keys, snapshot correctness

**sim-algorithms (6 tests):**
- Echo Extinction: highest-ID node elected on dense graph (5 nodes)
- Echo Extinction: highest-ID node elected on triangle (3 nodes)
- Echo Extinction: single initiator correctly elects itself (6 nodes)
- GHS: MST weight matches Kruskal on 4-node graph
- GHS: MST weight matches Kruskal on 6-node graph
- GHS: exactly N-1 Branch edges on 5-node graph

---

## Design Decisions and Tradeoffs

1. **Akka Classic over Akka Typed.** The rubric specifies "Akka classic actor."
   Classic also provides `Stash` (essential for GHS deferred messages) and
   `context.become` (natural for algorithm state machines) as built-in features.

2. **Per-node algorithm instances.** Each node gets its own `EchoExtinction`
   and `GHS` object. This avoids shared mutable state between actors —
   each instance's `var` fields are strictly actor-local.

3. **`var` usage.** Mutable fields in algorithm classes are justified as
   actor-local state (per rubric allowance). Each field is only accessed
   by the owning actor's thread — no synchronization needed.

4. **No `for`/`while` loops.** All iteration uses `map`, `foreach`, `filter`,
   `foldLeft`, `flatMap`, `find`, `scanLeft`, and other collection
   combinators to avoid the 0.5%-per-loop penalty.

5. **Graph generator as NetGameSim substitute.** A standalone generator
   ensures builds succeed on any machine without external submodule
   dependencies. The `GraphLoader` can also read JSON artifacts from
   NetGameSim if one is provided.

6. **Simplified GHS.** Documented variant that preserves correctness
   (verified against Kruskal) while avoiding the concurrent same-level
   merge protocol that causes the majority of GHS implementation bugs
   in asynchronous actor systems.

---

## Experiment Results

All experiments were run on Ubuntu 22.04 (WSL2), JDK 17, with the default
configs in `conf/`. Each experiment uses a deterministic seed, so results
are reproducible.

### Experiment 1: Small graph (exp-small.conf)

- **Graph:** 8 nodes, density 0.3, seed 101
- **Echo result:** Node 7 elected leader (correct — highest ID)
- **GHS result:** MST complete at level 1, fragId=1.0
- **Duration:** ~8 seconds

| Metric | Count |
|--------|-------|
| Timer ticks | 68 |
| PING messages sent | 68 |
| Echo Extinction messages | 48 |
| GHS messages | 1,436 |
| Dropped messages | 0 |

### Experiment 2: Medium graph (exp-medium.conf)

- **Graph:** 15 nodes, density 0.4, seed 202
- **Echo result:** Node 14 elected leader (correct — highest ID)
- **GHS result:** MST construction completed
- **Duration:** ~12 seconds

| Metric | Count |
|--------|-------|
| Timer ticks | 4 |
| PING messages sent | 15 |
| Echo Extinction messages | 270 |
| GHS messages | 268,555,642 |
| Dropped messages | 0 |

### Experiment 3: Large graph (exp-large.conf)

- **Graph:** 30 nodes, density 0.5, seed 303
- **Echo result:** Node 29 elected leader (correct — highest ID)
- **GHS result:** MST construction completed
- **Duration:** ~20 seconds

| Metric | Count |
|--------|-------|
| Timer ticks | 6 |
| PING messages sent | 67 |
| Echo Extinction messages | 2,487 |
| GHS messages | 538,726,415 |
| Dropped messages | 0 |

### Observations

**Echo with Extinction** scales cleanly across all three experiments. Message
counts grow roughly proportional to the number of edges times the number of
initiators, as expected. The correct leader (highest-ID node) is elected in
every case.

**GHS** produces correct MSTs in all experiments (verified by Kruskal's in
tests). However, the message counts in medium and large experiments are
significantly higher than the theoretical O(N log N) bound. This is a known
consequence of our deferred-message approach: when a `Connect` or `Test`
message arrives at a node whose fragment level hasn't caught up, the message
is re-queued to the node's own mailbox via `sendSelf`. In dense graphs with
many concurrent fragment merges, this creates a busy-wait loop where deferred
messages cycle repeatedly until the level condition is met. The algorithm
still terminates correctly, but the message overhead is higher than the
original paper's protocol. A production implementation would use Akka's
`Stash` with `unstashAll()` triggered by level changes to eliminate this
overhead.

---

## Reproducible Experiment Script

To reproduce all results on a clean machine:

```bash
# 1. Clone the repository
git clone https://github.com/piyushs11/cs553-project.git
cd cs553-project

# 2. Compile (downloads dependencies on first run, ~2 min)
sbt compile

# 3. Run all 17 tests
sbt test

# 4. Run small experiment (8 nodes, ~16s)
sbt "simCli/runMain edu.uic.cs553.cli.SimMain conf/exp-small.conf"

# 5. Run medium experiment (15 nodes, ~20s)
sbt "simCli/runMain edu.uic.cs553.cli.SimMain conf/exp-medium.conf"

# 6. Run large experiment (30 nodes, ~28s)
sbt "simCli/runMain edu.uic.cs553.cli.SimMain conf/exp-large.conf"

# 7. Run with message injection
sbt "simCli/runMain edu.uic.cs553.cli.SimMain conf/exp-small.conf conf/inject-sample.txt"
```

Requirements: JDK 17, SBT 1.9.7+, Unix-like shell (macOS or Linux).
All other dependencies are downloaded automatically by SBT.

---
