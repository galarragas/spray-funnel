name := "spray-funnel-examples"

Common.settings

libraryDependencies <++= scalaVersion(Common.runtimeDependencies(_))

val sprayVersion = "1.3.1"
val akkaVersion = "2.3.5"


libraryDependencies ++= Seq(
  "io.spray" %% "spray-client" % sprayVersion,
  "com.typesafe.akka" %% "akka-actor" % sprayVersion,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "org.slf4j" % "slf4j-api" % Common.sl4jVersion,
  "org.slf4j" % "slf4j-jcl" % Common.sl4jVersion
)

resolvers ++= Common.commonResolvers

resolvers += "Akka snapshots" at "http://repo.akka.io/snapshots/"

scalacOptions ++= Seq("-language:postfixOps", "-feature", "-unchecked", "-deprecation")