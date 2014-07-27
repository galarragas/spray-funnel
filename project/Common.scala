import sbt._
import Keys._

object Common {

  val settings: Seq[Setting[_]] = Seq (
    organization := "com.pragmasoft",
    version := "1.0-RC4",
    scalaVersion := "2.10.3"
  )

  val akkaVersion = "2.2.3"
  val sprayVersion = "1.3.1"
  val sl4jVersion = "1.7.5"


  val runtimeDependencies = Seq(
    "io.spray" % "spray-client" % "1.2.0",
    "io.spray" %% "spray-json" % "1.2.5",
    "com.typesafe.akka" %% "akka-actor" % akkaVersion
  )

  val testDependencies = Seq(
    "org.specs2" %%  "specs2" % "2.2.3" % "test",
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test",
    "org.mockito" % "mockito-all" % "1.9.5" % "test",

    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion % "test",
    "org.slf4j" % "slf4j-api" % sl4jVersion % "test",
    "org.slf4j" % "slf4j-jcl" % sl4jVersion % "test",
    "org.slf4j" % "slf4j-log4j12" % "1.7.5" % "test"
  )

  val commonResolvers = Seq(
    DefaultMavenRepository,
    "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
    "Spray repo" at "http://repo.spray.io"
  )

  def sprayImportVersion(scala_version: String) = scala_version match {
    case "2.11.0" => "1.3.1-20140423"
    case _ => sprayVersion
  }

  def sprayClientImport(scala_version: String) = scala_version match {
    case "2.11.0" => "io.spray" %% "spray-client" % sprayImportVersion(scala_version)
    case _ => "io.spray" % "spray-client" % sprayImportVersion(scala_version)
  }

  def runtimeDependencies(scala_version: String) =  Seq(
      "com.typesafe.akka" %% "akka-actor" % akkaVersion,
      sprayClientImport(scala_version)
    )


}