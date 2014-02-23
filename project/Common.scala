import sbt._
import Keys._

object Common {

  val settings: Seq[Setting[_]] = Seq (
    organization := "com.pragmasoft",
    version := "0.1",
    scalaVersion := "2.10.3"
  )

  val akkaVersion = "2.2.3"

  val sl4jVersion = "1.7.5"


  val runtimeDependencies = Seq(
    "io.spray" % "spray-client" % "1.2.0",
    "io.spray" %% "spray-json" % "1.2.5",
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "org.slf4j" % "slf4j-api" % sl4jVersion,
    "org.slf4j" % "slf4j-jcl" % sl4jVersion
  )

  val testDependencies = Seq(
    "org.scalatest" %% "scalatest" % "2.0" % "test",
    "com.github.tomakehurst" % "wiremock" % "1.43" % "test",
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test",
    "org.mockito" % "mockito-all" % "1.9.5" % "test"
  )

  val commonResolvers = Seq(
    DefaultMavenRepository,
    "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
    "Spray repo" at "http://repo.spray.io"
  )
}