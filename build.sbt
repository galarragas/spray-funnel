name := "spray-funnel-root"

Common.settings

lazy val sprayFunnel = project in file("./spray-funnel" )

lazy val root = project.in( file(".") )
  .aggregate(sprayFunnel)
