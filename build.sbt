import Dependencies._

ThisBuild / organization := "org.reactivemongo"

ThisBuild / autoAPIMappings := true

val baseArtifact = "reactivemongo-bson"

name := "reactivemongo-biːsən"

resolvers in ThisBuild ++= Seq(
  Resolver.sonatypeRepo("snapshots"),
  "Typesafe repository releases" at "https://repo.typesafe.com/typesafe/releases/")

ThisBuild / mimaFailOnNoPrevious := false

val commonSettings = Seq(
  scalacOptions in (Compile, doc) := (scalacOptions in Test).value ++ Seq(
    "-unchecked", "-deprecation", 
    /*"-diagrams", */"-implicits", "-skip-packages", "highlightextractor") ++
    Opts.doc.title(name.value),
  unmanagedSourceDirectories in Compile += {
    val base = (sourceDirectory in Compile).value

    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, n)) if n >= 13 => base / "scala-2.13+"
      case _                       => base / "scala-2.13-"
    }
  }
)

val reactivemongoShaded = Def.setting[ModuleID] {
  "org.reactivemongo" % "reactivemongo-shaded" % (version in ThisBuild).value
}

val spireLaws = Def.setting[ModuleID] {
  val sm = CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((major, minor)) => s"${major}.${minor}"
    case _ => "x"
  }

  ("org.typelevel" %% "spire-laws" % "0.17.0-M1").
    exclude("org.typelevel", s"discipline-scalatest_${sm}"),
}

libraryDependencies in ThisBuild ++= specsDeps.map(_ % Test)

lazy val api = (project in file("api")).settings(
  commonSettings ++ Seq(
    name := s"${baseArtifact}-api",
    description := "New BSON API",
    libraryDependencies ++= Seq(
      "org.specs2" %% "specs2-scalacheck" % specsVer,
      "org.typelevel" %% "discipline-specs2" % "1.0.0",
      spireLaws.value,
      "com.chuusai" %% "shapeless" % "2.3.3",
      "org.slf4j" % "slf4j-simple" % "1.7.29").map(_ % Test),
    libraryDependencies ++= Seq(reactivemongoShaded.value % Provided)
  ))

lazy val monocle = (project in file("monocle")).settings(
  commonSettings ++ Seq(
    name := s"${baseArtifact}-monocle",
    description := "Monocle utilities for BSON values",
    libraryDependencies ++= Seq(
      "com.github.julien-truffaut" %% "monocle-core" % {
        val ver = scalaBinaryVersion.value

        if (ver == "2.11") "1.6.0-M1"
        else "2.0.0-RC1"
      },
      slf4jApi % Test)
  )).dependsOn(api)

lazy val geo = (project in file("geo")).settings(
  commonSettings ++ Seq(
    name := s"${baseArtifact}-geo",
    description := "GeoJSON support for the BSON API",
    fork in Test := true,
    libraryDependencies ++= Seq(
      slf4jApi % Test)
  )
).dependsOn(api, monocle % Test)

lazy val benchmarks = (project in file("benchmarks")).
  enablePlugins(JmhPlugin).settings(
    libraryDependencies ++= Seq(reactivemongoShaded.value),
    publish := ({}),
    publishTo := None,
  ).dependsOn(api % "compile->test")

lazy val msbCompat = (project in file("msb-compat")).settings(
  commonSettings ++ Seq(
    name := s"${baseArtifact}-msb-compat",
    description := "Compatibility library with mongo-scala-bson",
    sourceDirectory := {
      // mongo-scala-bson no available for 2.13
      if (scalaBinaryVersion.value == "2.13") new java.io.File("/no/sources")
      else sourceDirectory.value
    },
    libraryDependencies ++= {
      if (scalaBinaryVersion.value != "2.13") {
        Seq("org.mongodb.scala" %% "mongo-scala-bson" % "2.7.0" % Provided)
      } else {
        Seq.empty
      }
    },
    scalacOptions := (Def.taskDyn {
      val opts = scalacOptions.value


      Def.task {
        if (scalaBinaryVersion.value == "2.13") {
          opts.filterNot(_.startsWith("-W"))
        } else {
          opts
        }
      }
    }).value
  )
).dependsOn(api)

lazy val root = (project in file(".")).settings(
  publish := ({}),
  publishTo := None
).aggregate(api, benchmarks, msbCompat, geo, monocle)
// !! Do not aggregate msbCompat as not 2.13
