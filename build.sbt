name := "spray-funnel-root"

Common.settings

net.virtualvoid.sbt.graph.Plugin.graphSettings

lazy val sprayFunnel = project in file("./spray-funnel" )
lazy val examples = (project in file("./examples" )).dependsOn(sprayFunnel)

lazy val root = project.in( file(".") )
  .aggregate(sprayFunnel)
