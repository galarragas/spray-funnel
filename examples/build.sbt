name := "spray-funnel-examples"

Common.settings

//libraryDependencies ++= Common.runtimeDependencies

//val sprayVersion = "2.4-SNAPSHOT"

val sprayVersion = "2.3.2"

libraryDependencies ++= Seq(
  "io.spray" % "spray-client" % "1.3.1",
  "io.spray" %% "spray-json" % "1.2.5",
  "com.typesafe.akka" %% "akka-actor" % sprayVersion,
  "com.typesafe.akka" %% "akka-slf4j" % sprayVersion,
  "org.slf4j" % "slf4j-api" % Common.sl4jVersion,
  "org.slf4j" % "slf4j-jcl" % Common.sl4jVersion
)

resolvers ++= Common.commonResolvers

resolvers += "Akka snapshots" at "http://repo.akka.io/snapshots/"

scalacOptions ++= Seq("-language:postfixOps", "-feature", "-unchecked", "-deprecation")