name := "spray-funnel"

Common.settings

net.virtualvoid.sbt.graph.Plugin.graphSettings


libraryDependencies ++= Common.runtimeDependencies

libraryDependencies ++= Common.testDependencies

resolvers ++= Common.commonResolvers

scalacOptions ++= Seq("-language:postfixOps", "-feature", "-unchecked", "-deprecation")