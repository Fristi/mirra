import sbt.Compile
import sbt.Keys.libraryDependencies

import laika.config._
import laika.format.Markdown
import laika.config.SyntaxHighlighting

ThisBuild / organization         := "io.github.fristi"
ThisBuild / organizationName     := "Fristi"
ThisBuild / scalaVersion         := "3.8.3"
ThisBuild / licenses             := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / homepage             := Some(url("https://github.com/Fristi/mirra"))
ThisBuild / developers           := List(
  Developer("Fristi", "Mark de Jong", "av3ng3r@gmail.com", url("https://github.com/Fristi"))
)
ThisBuild / sonatypeCredentialHost := "central.sonatype.com"
ThisBuild / sonatypeRepository     := "https://central.sonatype.com/api/v1/publisher"
ThisBuild / publishTo              := sonatypePublishToBundle.value
ThisBuild / versionScheme          := Some("early-semver")
ThisBuild / scmInfo := Some(ScmInfo(url("https://github.com/Fristi/mirra"), "scm:git@github.com:Fristi/mirra.git"))

val core =
  project.in(file("core"))
    .settings(commonSettings)
    .settings(
      name := "mirra-core",
      libraryDependencies ++= Seq(
        "org.typelevel" %% "cats-tagless-core" % "0.16.5",
        "dev.optics"    %% "monocle-core"      % "3.3.0",
        "org.scalameta" %% "munit"             % "1.3.0" % Test,
      ),
    )

val doobie =
  project.in(file("doobie"))
    .settings(commonSettings)
    .settings(
      name := "mirra-doobie",
      libraryDependencies ++= Seq(
        "org.tpolecat" %% "doobie-core" % "1.0.0-RC12"
      )
    )
    .dependsOn(core)

val munit =
  project.in(file("munit"))
    .settings(commonSettings)
    .settings(
      name := "mirra-munit",
      libraryDependencies ++= Seq(
        "org.scalameta" %% "munit"                   % "1.3.0",
        "org.typelevel" %% "munit-cats-effect"       % "2.2.0",
        "org.typelevel" %% "scalacheck-effect-munit" % "2.1.0"
      )
    )
    .dependsOn(core)

val zio =
  project.in(file("zio"))
    .settings(commonSettings)
    .settings(
      name := "mirra-zio",
      libraryDependencies ++= Seq(
        "dev.zio" %% "zio"          % "2.1.14",
        "dev.zio" %% "zio-test"     % "2.1.14" % Test,
        "dev.zio" %% "zio-test-sbt" % "2.1.14" % Test,
      ),
      testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    )
    .dependsOn(core)

val catsEffect =
  project.in(file("cats-effect"))
    .settings(commonSettings)
    .settings(
      name := "mirra-cats-effect",
      libraryDependencies ++= Seq(
        "org.typelevel" %% "cats-effect"       % "3.5.7",
        "org.scalameta" %% "munit"             % "1.3.0"   % Test,
        "org.typelevel" %% "munit-cats-effect" % "2.2.0"   % Test,
      )
    )
    .dependsOn(core)

val zioTest =
  project.in(file("zio-test"))
    .settings(commonSettings)
    .settings(
      name := "mirra-zio-test",
      libraryDependencies ++= Seq(
        "dev.zio" %% "zio"          % "2.1.14",
        "dev.zio" %% "zio-test"     % "2.1.14",
        "dev.zio" %% "zio-test-sbt" % "2.1.14",
      )
    )
    .dependsOn(core)

val skunk =
  project.in(file("skunk"))
    .settings(commonSettings)
    .settings(
      name := "mirra-skunk",
      libraryDependencies ++= Seq(
        "org.tpolecat" %% "skunk-core" % "1.0.0"
      )
    )
    .dependsOn(core)

val example =
  project.in(file("example"))
    .settings(commonSettings)
    .settings(
      publish / skip := true,
      libraryDependencies ++= Seq(
        "org.tpolecat"  %% "doobie-core"                        % "1.0.0-RC12",
        "org.tpolecat"  %% "doobie-postgres"                    % "1.0.0-RC12",
        "org.tpolecat"  %% "doobie-hikari"                      % "1.0.0-RC12",
        "dev.optics"    %% "monocle-macro"                      % "3.3.0",
        "com.dimafeng"  %% "testcontainers-scala-postgresql"    % "0.44.1",
        "com.dimafeng"  %% "testcontainers-scala-munit"         % "0.44.1",
        "dev.zio"       %% "zio"                                % "2.1.14"   % Test,
        "dev.zio"       %% "zio-test"                           % "2.1.14"   % Test,
        "dev.zio"       %% "zio-test-sbt"                       % "2.1.14"   % Test,
        "dev.zio"       %% "zio-interop-cats"                   % "23.1.0.3" % Test,
      ),
      testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    )
    .dependsOn(core, munit, doobie, skunk, zioTest)

lazy val docs = project
  .in(file("docs"))
  .enablePlugins(MirraSitePlugin)
  .dependsOn(core, doobie, skunk, munit, zioTest, catsEffect, zio)
  .settings(commonSettings)
  .settings(
    publish / skip := true,
    mdocIn := (Compile / sourceDirectory).value / "laika",
    libraryDependencies ++= Seq(
      "org.tpolecat" %% "doobie-postgres"                  % "1.0.0-RC12",
      "com.dimafeng" %% "testcontainers-scala-postgresql"  % "0.44.1",
      "com.dimafeng" %% "testcontainers-scala-munit"       % "0.44.1",
      "dev.zio"      %% "zio-interop-cats"                 % "23.1.0.3",
      "org.typelevel" %% "munit-cats-effect"               % "2.2.0",
      "dev.zio"      %% "zio-test"                         % "2.1.14",
    ),
  )

def commonSettings = Seq(
  scalacOptions += "-experimental"
)

lazy val root = (project in file("."))
  .aggregate(core, munit, doobie, skunk, zioTest, zio, catsEffect)
  .settings(
    publish / skip := true
  )