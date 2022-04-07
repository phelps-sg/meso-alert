name := """meso-alert"""
organization := "com.mesonomics"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.13.8"

libraryDependencies += guice
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "5.0.0" % Test
libraryDependencies += "org.bitcoinj" % "bitcoinj-core" % "0.16.1"
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.11"
// Adds additional packages into Twirl
//TwirlKeys.templateImports += "com.mesonomics.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "com.mesonomics.binders._"
