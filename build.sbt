name := """meso-alert"""
organization := "com.mesonomics"
maintainer := "steve@symbiotica.ai"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.13.8"

libraryDependencies += guice
libraryDependencies += "org.bitcoinj" % "bitcoinj-core" % "0.16.1"
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.11"
libraryDependencies += "com.github.nscala-time" %% "nscala-time" % "2.30.0"

libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "5.1.0" % Test
libraryDependencies += "org.awaitility" % "awaitility-scala" % "4.2.0" % Test
libraryDependencies += "org.scalamock" %% "scalamock" % "5.2.0" % Test
val AkkaVersion = "2.6.19"
libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % AkkaVersion % Test
libraryDependencies += "org.scalactic" %% "scalactic" % "3.2.12" % Test
libraryDependencies ++= List(
  "com.softwaremill.sttp.client3" %% "async-http-client-backend-monix" % "3.6.1",
  "com.softwaremill.sttp.client3" %% "circe" % "3.6.1",
  "io.circe" %% "circe-generic" % "0.14.1"
)
libraryDependencies ++= Seq(
  "com.typesafe.slick" %% "slick" % "3.3.3",
  "com.typesafe.slick" %% "slick-hikaricp" % "3.3.3",
  "org.postgresql" % "postgresql" % "42.3.4",
  "com.github.tminglei" %% "slick-pg_play-json" % "0.20.3",
  "com.dimafeng" %% "testcontainers-scala-scalatest" % "0.40.7" % "test",
  "com.dimafeng" %% "testcontainers-scala-postgresql" % "0.40.7" % "test"
)
// Adds additional packages into Twirl
//TwirlKeys.templateImports += "com.mesonomics.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "com.mesonomics.binders._"
