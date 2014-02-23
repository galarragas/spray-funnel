name := "spray-funnel-root"

lazy val sprayFunnel = project in file("./spray-funnel" )

lazy val root = project.in( file(".") )
  .aggregate(sprayFunnel)
