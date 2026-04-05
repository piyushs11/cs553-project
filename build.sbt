ThisBuild / scalaVersion := "3.3.3"
ThisBuild / version      := "0.1.0"
ThisBuild / organization := "edu.uic.cs553"

val akkaVersion = "2.8.5"

// sim-core: pure data model, graph, config — no Akka dependency
lazy val simCore = project
  .in(file("sim-core"))
  .settings(
    name := "sim-core",
    libraryDependencies ++= Seq(
      "com.typesafe"   % "config"        % "1.4.3",
      "io.circe"      %% "circe-core"    % "0.14.7",
      "io.circe"      %% "circe-generic" % "0.14.7",
      "io.circe"      %% "circe-parser"  % "0.14.7",
      "org.scalatest" %% "scalatest"     % "3.2.17" % Test,
    )
  )

// sim-runtime-akka: NodeActor, Simulator, channels
lazy val simRuntime = project
  .in(file("sim-runtime-akka"))
  .dependsOn(simCore)
  .settings(
    name := "sim-runtime-akka",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor"              % akkaVersion,
      "com.typesafe.akka" %% "akka-actor-typed"         % akkaVersion,
      "com.typesafe.akka" %% "akka-slf4j"               % akkaVersion,
      "ch.qos.logback"     % "logback-classic"           % "1.4.11",
      "com.typesafe.akka" %% "akka-testkit"              % akkaVersion % Test,
      "com.typesafe.akka" %% "akka-actor-testkit-typed"  % akkaVersion % Test,
      "org.scalatest"     %% "scalatest"                 % "3.2.17"   % Test,
    )
  )

// sim-algorithms: Echo with Extinction + GHS
lazy val simAlgorithms = project
  .in(file("sim-algorithms"))
  .dependsOn(simRuntime)
  .settings(
    name := "sim-algorithms",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-testkit"             % akkaVersion % Test,
      "com.typesafe.akka" %% "akka-actor-testkit-typed"  % akkaVersion % Test,
      "org.scalatest"     %% "scalatest"                 % "3.2.17"   % Test,
    )
  )

// sim-cli: entry points
lazy val simCli = project
  .in(file("sim-cli"))
  .dependsOn(simAlgorithms)
  .settings(
    name := "sim-cli",
  )

// Root project aggregates all modules
lazy val root = project
  .in(file("."))
  .aggregate(simCore, simRuntime, simAlgorithms, simCli)
  .settings(
    name := "CS553DistributedSim",
    publish / skip := true,
  )