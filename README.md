# CS553 — Distributed Computing Systems Project

A configurable simulation pipeline that turns a generated graph into a live Akka
actor system and runs two distributed algorithms on top of it:

- **Echo with Extinction** — leader election on a general connected graph
- **Gallager–Humblet–Spira (GHS)** — distributed minimum spanning tree

---

## System requirements

- JDK 17 (tested on Temurin 17.0.18)
- SBT 1.9.7 or later
- Unix-like shell (macOS, Linux, or WSL2 Ubuntu on Windows)

No other dependencies needed — SBT downloads everything else on first build.

---

## Build and test

From the project root:

```bash
sbt compile
sbt test
```

All 17 tests should pass across 4 modules:
- `sim-core` — 8 tests (graph validation, generation, serialization)
- `sim-runtime-akka` — 3 tests (metrics collector)
- `sim-algorithms` — 6 tests (Echo Extinction correctness + GHS vs Kruskal)

---

## Run an experiment

Three pre-defined experiment configs are provided in `conf/`:

```bash
# Small graph: 8 nodes, sparse — good for watching log output
sbt "simCli/runMain edu.uic.cs553.cli.SimMain conf/exp-small.conf"

# Medium graph: 15 nodes, moderate density
sbt "simCli/runMain edu.uic.cs553.cli.SimMain conf/exp-medium.conf"

# Large graph: 30 nodes, dense — stress test
sbt "simCli/runMain edu.uic.cs553.cli.SimMain conf/exp-large.conf"
```

### Injecting external messages

You can feed messages to input nodes via a file:

```bash
sbt "simCli/runMain edu.uic.cs553.cli.SimMain conf/exp-small.conf conf/inject-sample.txt"
```

Each line of the injection file is `nodeId,messageKind,payload`, e.g.:
```text
2,WORK,task-1
3,GOSSIP,hello
```

## Project structure

```text
cs553-project/
├── build.sbt                 # multi-module SBT build (Scala 3.3.3)
├── project/
│   ├── build.properties
│   └── plugins.sbt
├── sim-core/                 # graph model, generator, config, loader
│   └── src/{main,test}/scala/edu/uic/cs553/core/
├── sim-runtime-akka/         # NodeActor, Simulator, plugin interface, metrics
│   └── src/{main,test}/scala/edu/uic/cs553/runtime/
├── sim-algorithms/           # EchoExtinction + GHS
│   └── src/{main,test}/scala/edu/uic/cs553/algorithms/
├── sim-cli/                  # SimMain entry point
│   └── src/main/scala/edu/uic/cs553/cli/
├── conf/                     # experiment configs + sample injection
└── docs/
    └── report.md             # design report
```

---

## Opening in IntelliJ IDEA

1. Open IntelliJ → File → Open → select the project root directory
2. Choose "Open as Project" → IntelliJ detects `build.sbt` and imports as an SBT project
3. When prompted, select JDK 17
4. The four modules (simCore, simRuntime, simAlgorithms, simCli) appear in the project view

No manual path tweaks required.

---

## Configuration

All runtime parameters live in HOCON config files — no hardcoded values.

Defaults are in `sim-core/src/main/resources/reference.conf` and include:
- Graph generation parameters (node count, density, seed)
- Message types and edge-label defaults
- Traffic parameters (tick interval, default PDF, distribution family)
- Initiators (timer nodes, input nodes)
- Algorithm selection and run duration

Per-experiment files in `conf/` override only the fields they need to change.

### Reproducibility

Every run uses a deterministic seed (`sim.seed` in the config). Two runs
with the same config produce the same graph topology and the same PDF sampling
sequence. Graph generator uses `scala.util.Random(seed)`; each `NodeActor`
derives its PDF sampler from `seed + nodeId`.

---

## Documentation

The design report is at `docs/report.md`. It covers system architecture,
algorithm design, message protocols, experiment results, and a reproducible
experiment script.

---

## Expected output

Each run produces:

1. **Startup log** — graph validation, actor initialization, algorithm start
2. **Algorithm trace** — Wave/Echo propagation for Echo Extinction,
   Wakeup/Connect/Initiate/Test/Accept/Reject/Report for GHS
3. **Results** — `*** ELECTED LEADER ***` (highest-ID node wins Echo
   Extinction); `*** MST COMPLETE ***` (GHS builds a valid minimum
   spanning tree)
4. **Metrics report** at shutdown — message counts by type, per-algorithm
   traffic, timer ticks, dropped-message counts

---

## Notes on implementation choices

This project follows the professor's CourseProject.MD closely but makes three
documented variants:

1. **Simplified GHS**. Our GHS uses a deterministic tiebreak (lower fragment
   ID becomes the core after a merge) instead of the concurrent same-level
   merge protocol from the 1983 paper. This sacrifices the O(N log N) message
   complexity bound for implementation tractability; correctness against
   Kruskal's output is verified by tests. See `docs/report.md` for details.

2. **Cinnamon telemetry (commented out)**. Cinnamon requires Lightbend
   commercial credentials. The project includes full Cinnamon configuration
   in `build.sbt`, `project/plugins.sbt`, and `application.conf` — all
   commented out. To enable: (a) set `LIGHTBEND_COMMERCIAL_USERNAME` and
   `LIGHTBEND_COMMERCIAL_PASSWORD` environment variables, (b) uncomment
   the Cinnamon lines in `build.sbt` and `plugins.sbt`, (c) uncomment
   the `cinnamon` block in `application.conf`. A custom `MetricsCollector`
   provides equivalent functionality without requiring credentials.

3. **Graph generation without NetGameSim submodule**. We provide a standalone
   `GraphGenerator` that produces graphs satisfying GHS's requirements
   (connected, unique edge weights) with configurable size and density. The
   system also loads graphs from JSON via `GraphLoader`, so a NetGameSim
   artifact could be plugged in if desired.
