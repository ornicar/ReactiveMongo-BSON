import sbt._

object Dependencies {
  val specsVer = "4.8.1"

  val specsDeps = Seq(
    "org.specs2" %% "specs2-core" % specsVer,
    "org.specs2" %% "specs2-junit" % specsVer)

  val slf4jApi = "org.slf4j" % "slf4j-simple" % "1.7.29"
}
