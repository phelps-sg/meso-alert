name := """meso-alert"""
organization := "com.mesonomics"
maintainer := "steve@symbiotica.ai"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

inThisBuild(
  List(
    scalaVersion := "2.13.8",
    scalafixScalaBinaryVersion := "2.13",
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    scalacOptions += "-Ywarn-unused"
  )
)

Test / javaOptions += "-Dwebdriver.gecko.driver=/usr/local/bin/geckodriver"

libraryDependencies += guice
libraryDependencies += "org.bitcoinj" % "bitcoinj-core" % "0.16.1"
libraryDependencies += "org.abstractj.kalium" % "kalium" % "0.8.0"

val AkkaVersion = "2.6.19"
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "5.1.0" % Test
libraryDependencies += "org.awaitility" % "awaitility-scala" % "4.2.0" % Test
libraryDependencies += "org.scalamock" %% "scalamock" % "5.2.0" % Test
libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % AkkaVersion % Test
libraryDependencies += "org.scalactic" %% "scalactic" % "3.2.12" % Test
libraryDependencies += "com.google.guava" % "guava" % "31.1-jre" % Test

libraryDependencies ++= List(
  "com.softwaremill.sttp.client3" %% "async-http-client-backend-monix" % "3.6.2",
  "com.softwaremill.sttp.client3" %% "circe" % "3.6.2",
  "io.circe" %% "circe-generic" % "0.14.2"
)

libraryDependencies ++= Seq(
  "com.typesafe.slick" %% "slick" % "3.3.3",
  "com.typesafe.slick" %% "slick-hikaricp" % "3.3.3",
  "org.postgresql" % "postgresql" % "42.3.6",
  "com.github.tminglei" %% "slick-pg_play-json" % "0.20.3",
  "com.dimafeng" %% "testcontainers-scala-scalatest" % "0.40.8" % "test",
  "com.dimafeng" %% "testcontainers-scala-postgresql" % "0.40.8" % "test"
)

libraryDependencies ++= List(
  "com.slack.api" % "bolt" % "1.22.2",
  "com.slack.api" % "bolt-servlet" %  "1.22.2",
  "com.slack.api" % "bolt-jetty" %  "1.22.2",
)
