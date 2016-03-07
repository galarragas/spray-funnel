import sbt._
import Keys._

object Common {

  val settings: Seq[Setting[_]] = Seq (
    organization := "com.pragmasoft",
    version := "1.2-spray1.3",
    scalaVersion := "2.11.7",
    crossScalaVersions := Seq("2.11.0"),
    licenses += ("Apache-2.0", url("https://opensource.org/licenses/Apache-2.0"))
  )

  val sl4jVersion = "1.7.5"
  val sprayVersion = "1.3.1"
  val akkaVersion = "2.3.2"

  def testDependencies(scala_version: String) = Seq(
    "org.specs2" %%  "specs2" % "2.5" % "test",
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test",
    "org.mockito" % "mockito-all" % "1.9.5" % "test",

    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion % "test",
    "org.slf4j" % "slf4j-api" % sl4jVersion % "test",
    "org.slf4j" % "slf4j-jcl" % sl4jVersion % "test",
    "org.slf4j" % "slf4j-log4j12" % "1.7.5" % "test",
    "org.scalaz.stream" %% "scalaz-stream" % "0.7.2a" % "test",


    "io.spray" %% "spray-routing" % sprayImportVersion(scala_version) % "test"
  )

  val commonResolvers = Seq(
    DefaultMavenRepository,
    "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
    "Spray repo" at "http://repo.spray.io"
  )

  def sprayImportVersion(scala_version: String) = scala_version match {
    case "2.11.0" => "1.3.1"
    case _ => sprayVersion
  }

  def runtimeDependencies(scala_version: String, provided: Boolean = false) = {
    val deps = Seq(
      "com.typesafe.akka" %% "akka-actor" % akkaVersion,
      "io.spray" %% "spray-client" % sprayImportVersion(scala_version)
    )

    if(provided)
      deps map { _ % "provided" }
    else
      deps
  }


}
