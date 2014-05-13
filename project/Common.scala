import sbt._
import Keys._

object Common {

  val settings: Seq[Setting[_]] = Seq (
    organization := "com.pragmasoft",
    version := "1.0-RC3-spray1.3",
    scalaVersion := "2.10.3",
    crossScalaVersions := Seq("2.11.0")
  )

  val sl4jVersion = "1.7.5"

  val isSpray1_3 = true

  val sprayVersion = if(isSpray1_3) "1.3.1" else "1.2.0"
  val akkaVersion = if(isSpray1_3) "2.3.2" else "2.2.3"


  val runtimeDependencies = Seq(
    "io.spray" % "spray-client" % sprayVersion,
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "org.slf4j" % "slf4j-api" % sl4jVersion,
    "org.slf4j" % "slf4j-jcl" % sl4jVersion
  )

  val testDependencies = Seq(
    "org.specs2" %%  "specs2" % "2.3.12" % "test",
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