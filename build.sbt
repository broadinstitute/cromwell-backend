import com.typesafe.sbt.GitPlugin.autoImport._
import com.typesafe.sbt.SbtGit.GitCommand

name := "Cromwell Backend"

scalaVersion := "2.11.7"

organization := "org.broadinstitute"

// Upcoming release, or current if we're on the master branch
git.baseVersion := "0.1"

// Shorten the git commit hash
git.gitHeadCommit := git.gitHeadCommit.value map { _.take(7) }

// Travis will deploy tagged releases, add -SNAPSHOT for all local builds
git.gitUncommittedChanges := true

versionWithGit

assemblyJarName in assembly := "cromwell-backend-" + git.baseVersion.value + ".jar"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

resolvers ++= Seq("Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
                  "Broad Artifactory Releases" at "https://artifactory.broadinstitute.org/artifactory/libs-release/",
                  "Broad Artifactory Snapshots" at "https://artifactory.broadinstitute.org/artifactory/libs-snapshot/")

libraryDependencies ++= {
  val akkaV = "2.3.12"
  Seq("com.typesafe.akka" %% "akka-actor" % akkaV,
    "com.typesafe.akka" %% "akka-actor" % akkaV,
    "com.typesafe.akka" %% "akka-slf4j" % akkaV,
    "com.typesafe.akka" %% "akka-testkit" % akkaV % "test",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0",
    "ch.qos.logback" % "logback-classic" % "1.1.3",
    "org.scalatest" % "scalatest_2.11" % "2.2.5" % "test",
    "org.mockito" % "mockito-core" % "1.9.5",
    "com.github.pathikrit" %% "better-files" % "2.13.0",
    "commons-codec" % "commons-codec" % "1.10",
    "org.broadinstitute" %% "wdl4s" % "0.2")
}

// The reason why -Xmax-classfile-name is set is because this will fail
// to build on Docker otherwise.  The reason why it's 200 is because it
// fails if the value is too close to 256 (even 254 fails).  For more info:
//
// https://github.com/sbt/sbt-assembly/issues/69
// https://github.com/scala/pickling/issues/10
scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature", "-Xmax-classfile-name", "200")
