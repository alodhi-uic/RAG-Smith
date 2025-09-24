ThisBuild / scalaVersion := "3.5.1"

lazy val hadoopVer = "3.3.6"
lazy val luceneVer = "9.10.0"

libraryDependencies ++= Seq(
  "org.apache.hadoop" % "hadoop-client" % hadoopVer,
  "org.apache.lucene" % "lucene-core" % luceneVer,
  "org.apache.lucene" % "lucene-analysis-common" % luceneVer,
  "org.apache.pdfbox" % "pdfbox" % "2.0.31",
  "com.softwaremill.sttp.client3" %% "core"  % "3.9.5",
  "com.softwaremill.sttp.client3" %% "circe" % "3.9.5",
  "io.circe"                      %% "circe-generic" % "0.14.9",
  "io.circe"                      %% "circe-parser"  % "0.14.9",
  "com.typesafe" % "config" % "1.4.3",
  "ch.qos.logback" % "logback-classic" % "1.5.6",
  "org.scalatest" %% "scalatest" % "3.2.19" % Test
)

assembly / mainClass := Some("mr.Driver")
