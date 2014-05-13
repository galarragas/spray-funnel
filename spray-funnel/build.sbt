import sbt.Keys._

name := "spray-funnel"

Common.settings

net.virtualvoid.sbt.graph.Plugin.graphSettings

libraryDependencies <++= scalaVersion(Common.runtimeDependencies(_))

libraryDependencies ++= Common.testDependencies

resolvers ++= Common.commonResolvers

scalacOptions ++= Seq("-language:postfixOps", "-feature", "-unchecked", "-deprecation")