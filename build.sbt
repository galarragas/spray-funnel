name := "spray-funnel-root"

Common.settings

lazy val sprayFunnel = project in file("./spray-funnel" )
lazy val examples = (project in file("./examples" )).dependsOn(sprayFunnel)

lazy val root = project.in( file(".") )
  .aggregate(sprayFunnel)
