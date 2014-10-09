name := "spray-funnel"

Common.settings

libraryDependencies ++= Common.runtimeDependencies

libraryDependencies <++= scalaVersion(Common.testDependencies(_))

resolvers ++= Common.commonResolvers

scalacOptions ++= Seq("-language:postfixOps", "-feature", "-unchecked", "-deprecation")