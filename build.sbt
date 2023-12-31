name := """meso-alert"""
organization := "com.mesonomics"
maintainer := "steve@symbiotica.ai"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

inThisBuild(
  List(
    scalaVersion := "2.13.10",
    scalafixScalaBinaryVersion := "2.13",
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    scalacOptions += "-Ywarn-unused",
    scalacOptions += "-Xcheckinit"
  )
)

libraryDependencies += guice
libraryDependencies += "org.bitcoinj" % "bitcoinj-core" % "0.16.2"
libraryDependencies += "org.abstractj.kalium" % "kalium" % "0.8.0"
libraryDependencies += "com.github.daddykotex" %% "courier" % "3.2.0"

libraryDependencies ++= List(
  "com.softwaremill.sttp.client3" %% "async-http-client-backend-monix" % "3.8.8",
  "com.softwaremill.sttp.client3" %% "circe" % "3.8.8",
  "io.circe" %% "circe-generic" % "0.14.3"
)

val slickVersion = "3.4.1"
libraryDependencies ++= Seq(
  "com.typesafe.slick" %% "slick" % slickVersion,
  "com.typesafe.slick" %% "slick-hikaricp" % slickVersion,
  "org.postgresql" % "postgresql" % "42.5.1"
)

libraryDependencies += "com.github.tminglei" %% "slick-pg_play-json" % "0.21.1"

val boltVersion = "1.27.3"
libraryDependencies ++= List(
  "com.slack.api" % "bolt" % boltVersion,
  "com.slack.api" % "bolt-servlet" %  boltVersion,
  "com.slack.api" % "bolt-jetty" %  boltVersion
)

val AkkaVersion = "2.7.0"
libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-persistence" % AkkaVersion,
  "com.typesafe.akka" %% "akka-actor" % AkkaVersion,
  "com.typesafe.akka" %% "akka-remote" % AkkaVersion,
  "com.typesafe.akka" %% "akka-serialization-jackson" % AkkaVersion,
  "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion,
  "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-persistence-testkit" % AkkaVersion % Test
)

val jwtScalaVersion = "9.1.2"
libraryDependencies ++= Seq(
  "com.github.jwt-scala" %% "jwt-play" % jwtScalaVersion,
  "com.github.jwt-scala" %% "jwt-core" % jwtScalaVersion,
  "com.auth0" % "jwks-rsa" % "0.21.2"
)

libraryDependencies += "com.mesonomics" %% "play-hmac-signatures" % "0.5.5"

dependencyOverrides += "org.scala-lang.modules" %% "scala-parser-combinators" % "2.1.1"


Test / testForkedParallel := true
Test / parallelExecution := true
Test / javaOptions += "-Dwebdriver.gecko.driver=/usr/local/bin/geckodriver"
Test / javaOptions += "-Dconfig.resource=application.test.conf"
Test / scalaSource := baseDirectory.value / "test/scala"
Test / resourceDirectory := baseDirectory.value / "test/resources"

libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "5.1.0" % Test
libraryDependencies += "org.scalatestplus" %% "selenium-4-1" % "3.2.12.1" % "test"
libraryDependencies += "org.awaitility" % "awaitility-scala" % "4.2.0" % Test
libraryDependencies += "org.scalamock" %% "scalamock" % "5.2.0" % Test
libraryDependencies += "org.scalactic" %% "scalactic" % "3.2.15" % Test
libraryDependencies += "com.google.guava" % "guava" % "31.1-jre" % Test
val testContainersVersion = "0.40.12"
libraryDependencies ++= Seq(
  "com.dimafeng" %% "testcontainers-scala-scalatest" % testContainersVersion % Test,
  "com.dimafeng" %% "testcontainers-scala-postgresql" % testContainersVersion % Test
)
