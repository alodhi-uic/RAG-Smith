ThisBuild / scalaVersion := "3.5.1"
ThisBuild / organization := "rag441"
ThisBuild / version := "0.1.0"

libraryDependencies ++= Seq(
  // Lucene vector/HNSW + analysis
  "org.apache.lucene" % "lucene-core" % "9.10.0",
  "org.apache.lucene" % "lucene-analysis-common" % "9.10.0",
  // PDF, HTTP, JSON
  "org.apache.pdfbox" % "pdfbox" % "2.0.31",
  "com.softwaremill.sttp.client3" %% "core"  % "3.9.5",
  "com.softwaremill.sttp.client3" %% "circe" % "3.9.5",
  "io.circe" %% "circe-generic" % "0.14.9",
  "io.circe" %% "circe-parser"  % "0.14.9",
  // Typesafe Config + logging
  "com.typesafe" % "config" % "1.4.3",
  "ch.qos.logback" % "logback-classic" % "1.5.6",
  "org.slf4j" % "slf4j-api" % "2.0.16",
  // Hadoop MapReduce API (compile against; EMR provides runtime)
  "org.apache.hadoop" % "hadoop-common" % "3.3.6" % Provided,
  "org.apache.hadoop" % "hadoop-mapreduce-client-core" % "3.3.6" % Provided,
  // Tests
  "org.scalatest" %% "scalatest" % "3.2.19" % Test
)

Compile / run / fork := true
Compile / javaOptions ++= Seq("-Xmx4G", "-XX:+UseG1GC")
Test / parallelExecution := false
