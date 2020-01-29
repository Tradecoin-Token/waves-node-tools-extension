/* IDEA notes
 * May require to delete .idea and re-import with all checkboxes
 * Worksheets may not work: https://youtrack.jetbrains.com/issue/SCL-6726
 * To work with worksheets, make sure:
   1. You've selected the appropriate project
   2. You've checked "Make project before run"
 */

import sbt.Keys._
import sbt._
import sbt.internal.inc.ReflectUtilities
import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}

lazy val lang =
  crossProject(JSPlatform, JVMPlatform)
    .withoutSuffixFor(JVMPlatform)
    .crossType(CrossType.Full)
    .settings(
      coverageExcludedPackages := ".*",
      test in assembly := {},
      libraryDependencies ++= Dependencies.lang.value ++ Dependencies.test,
      inConfig(Compile)(
        Seq(
          sourceGenerators += Tasks.docSource,
          PB.targets += scalapb.gen(flatPackage = true) -> (sourceManaged in Compile).value,
          PB.protoSources := Seq(baseDirectory.value.getParentFile / "shared" / "src" / "main" / "protobuf"),
          PB.deleteTargetDirectory := false
        )
      )
    )

lazy val langJVM = lang.jvm
  .settings(
    name := "RIDE Compiler",
    normalizedName := "lang",
    description := "The RIDE smart contract language compiler",
    libraryDependencies += "org.scala-js" %% "scalajs-stubs" % "1.0.0" % Provided
  )

lazy val `lang-testkit` = project
  .dependsOn(langJVM)
  .in(file("lang/testkit"))
  .settings(
    libraryDependencies ++= Dependencies.test.map(_.withConfigurations(Some("compile")))
  )

lazy val node             = project.dependsOn(langJVM, `lang-testkit` % "test")
lazy val `node-tools`     = project.dependsOn(node % "compile;runtime->provided")

lazy val root = (project in file("."))
  .aggregate(
    langJVM,
    node
  )

inScope(Global)(
  Seq(
    scalaVersion := "2.12.9",
    organization := "com.wavesplatform",
    organizationName := "Waves Platform",
    V.fallback := (1, 1, 5),
    organizationHomepage := Some(url("https://wavesplatform.com")),
    scmInfo := Some(ScmInfo(url("https://github.com/wavesplatform/Waves"), "git@github.com:wavesplatform/Waves.git", None)),
    licenses := Seq(("MIT", url("https://github.com/wavesplatform/Waves/blob/master/LICENSE"))),
    scalacOptions ++= Seq(
      "-feature",
      "-deprecation",
      "-unchecked",
      "-language:higherKinds",
      "-language:implicitConversions",
      "-language:postfixOps",
      "-Ywarn-unused:-implicits",
      "-Xlint",
      "-Ypartial-unification",
      "-opt:l:inline",
      "-opt-inline-from:**"
    ),
    crossPaths := false,
    scalafmtOnCompile := false,
    dependencyOverrides ++= Dependencies.enforcedVersions.value,
    cancelable := true,
    logBuffered := false,
    coverageExcludedPackages := ".*",
    parallelExecution := false,
    testListeners := Seq.empty, // Fix for doubled test reports
    /* http://www.scalatest.org/user_guide/using_the_runner
     * o - select the standard output reporter
     * I - show reminder of failed and canceled tests without stack traces
     * D - show all durations
     * O - drop InfoProvided events
     * F - show full stack traces
     * u - select the JUnit XML reporter with output directory
     */
    testOptions += Tests.Argument("-oIDOF", "-u", "target/test-reports"),
    testOptions += Tests.Setup(_ => sys.props("sbt-testing") = "true"),
    concurrentRestrictions := {
      val threadNumber = Option(System.getenv("SBT_THREAD_NUMBER")).fold(1)(_.toInt)
      Seq(Tags.limit(Tags.ForkedTestGroup, threadNumber))
    },
    network := Network(sys.props.get("network")),
    resolvers += Resolver.sonatypeRepo("snapshots")
  )
)

// ThisBuild options
git.useGitDescribe := true
git.uncommittedSignifier := Some("DIRTY")

// root project settings
// https://stackoverflow.com/a/48592704/4050580
def allProjects: List[ProjectReference] = ReflectUtilities.allVals[Project](this).values.toList map { p =>
  p: ProjectReference
}

lazy val cleanAll = taskKey[Unit]("Clean all projects")
cleanAll := clean.all(ScopeFilter(inProjects(allProjects: _*), inConfigurations(Compile))).value

lazy val packageAll = taskKey[Unit]("Package all artifacts")
packageAll := Def
  .sequential(
    root / cleanAll,
    Def.task {
      (node / assembly).value
      (node / Debian / packageBin).value
      (`node-tools` / Universal / packageZipTarball).value
      (`node-tools` / Debian / packageBin).value
    }
  )
  .value
