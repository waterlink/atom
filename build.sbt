name := """atom"""

version := "1.0"

scalaVersion := "2.11.6"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4.1",
  "com.typesafe.akka" %% "akka-testkit" % "2.4.1" % "test",
  "com.typesafe.akka" %% "akka-cluster" % "2.4.1",
  "org.scalatest" %% "scalatest" % "2.2.4" % "test")
