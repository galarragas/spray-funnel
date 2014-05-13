name := "spray-funnel-examples"

Common.settings

libraryDependencies <++= scalaVersion(Common.runtimeDependencies(_))

libraryDependencies ++= Seq(
  "io.spray" %% "spray-client" % Common.sprayVersion,
  "com.typesafe.akka" %% "akka-actor" % Common.sprayVersion,
  "com.typesafe.akka" %% "akka-slf4j" % Common.sprayVersion,
  "org.slf4j" % "slf4j-api" % Common.sl4jVersion,
  "org.slf4j" % "slf4j-jcl" % Common.sl4jVersion
)

resolvers ++= Common.commonResolvers

resolvers += "Akka snapshots" at "http://repo.akka.io/snapshots/"

scalacOptions ++= Seq("-language:postfixOps", "-feature", "-unchecked", "-deprecation")