import sbt.Compile
import sbt.Keys.libraryDependencies

val core =
  project.in(file("core"))
    .settings(commonSettings)
    .settings(
      libraryDependencies ++= Seq(
        "org.typelevel" %% "cats-tagless-core" % "0.16.5",
        "dev.optics" %% "monocle-core"  % "3.3.0",
      ),

    )

val doobie =
  project.in(file("doobie"))
    .settings(commonSettings)
    .settings(
      libraryDependencies ++= Seq(
        "org.tpolecat"      %% "doobie-core"            % "1.0.0-RC12"
      )
    )
    .dependsOn(core)

val munit =
  project.in(file("munit"))
    .settings(commonSettings)
    .settings(
      libraryDependencies ++= Seq(
        "org.scalameta" %% "munit" % "1.3.0",
        "org.typelevel" %% "munit-cats-effect" % "2.2.0",
        "org.typelevel" %% "scalacheck-effect-munit" % "2.1.0"
      )
    )
    .dependsOn(core)

def commonSettings = Seq(
  scalaVersion := "3.8.3",
  scalacOptions += "-experimental"
)


val example =
  project.in(file("example"))
    .settings(commonSettings)
    .settings(
      libraryDependencies ++= Seq(
        "org.tpolecat"      %% "doobie-core"            % "1.0.0-RC12",
        "org.tpolecat"      %% "doobie-postgres"        % "1.0.0-RC12",
        "org.tpolecat"      %% "doobie-hikari"          % "1.0.0-RC12",
        "dev.optics" %% "monocle-macro" % "3.3.0",
        "com.dimafeng" %% "testcontainers-scala-postgresql" % "0.44.1",
        "com.dimafeng" %% "testcontainers-scala-munit" % "0.44.1"
      )
    )
    .dependsOn(core, munit, doobie)