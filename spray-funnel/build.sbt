name := "spray-funnel"

Common.settings

libraryDependencies ++= Common.runtimeDependencies

libraryDependencies ++= Common.testDependencies

resolvers ++= Common.commonResolvers

scalacOptions ++= Seq("-language:postfixOps", "-feature", "-unchecked", "-deprecation")