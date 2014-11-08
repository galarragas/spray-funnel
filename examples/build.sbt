name := "spray-funnel-examples"

Common.settings

libraryDependencies <++= scalaVersion(Common.runtimeDependencies(_, true))

resolvers ++= Common.commonResolvers

resolvers += "Akka snapshots" at "http://repo.akka.io/snapshots/"

scalacOptions ++= Seq("-language:postfixOps", "-feature", "-unchecked", "-deprecation")