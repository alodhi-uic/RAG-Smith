ThisBuild / scalaVersion := "3.5.1"

lazy val hadoopVer = "3.3.6"
lazy val luceneVer = "9.10.0"

libraryDependencies ++= Seq(
  // Hadoop is on the runtime classpath when you run with `hadoop jar` or on EMR.
  "org.apache.hadoop" % "hadoop-client" % hadoopVer % "provided"
    exclude("javax.xml.bind", "jaxb-api")
    exclude("javax.activation", "activation"),

  // Lucene (vector search)
  "org.apache.lucene" % "lucene-core" % luceneVer,
  "org.apache.lucene" % "lucene-analysis-common" % luceneVer,

  // PDF extraction
  "org.apache.pdfbox" % "pdfbox" % "2.0.31",

  // HTTP + JSON (Ollama)
  "com.softwaremill.sttp.client3" %% "core"  % "3.9.5",
  "com.softwaremill.sttp.client3" %% "circe" % "3.9.5",
  "io.circe"                      %% "circe-generic" % "0.14.9",
  "io.circe"                      %% "circe-parser"  % "0.14.9",

  // Config & Logging
  "com.typesafe" % "config" % "1.4.3",
  "ch.qos.logback" % "logback-classic" % "1.5.6",
  // (optional) silence PDFBox legacy log4j chatter
  "org.slf4j" % "log4j-over-slf4j" % "2.0.16",

  // Tests
  "org.scalatest" %% "scalatest" % "3.2.19" % Test
)

// Prefer jakarta over legacy javax to avoid clashes
dependencyOverrides += "jakarta.xml.bind" % "jakarta.xml.bind-api" % "2.3.3"

// Fat jar entrypoint (for MR driver)
assembly / mainClass := Some("mr.Driver")

// Merge strategy to handle duplicate resources
import sbtassembly.MergeStrategy
import sbtassembly.PathList

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "MANIFEST.MF")                         => MergeStrategy.discard
  case PathList("META-INF", xs @ _*) if xs.exists(_.toLowerCase == "services") => MergeStrategy.concat
  case PathList("META-INF", "spring.factories")                    => MergeStrategy.concat
  case PathList("META-INF", "io.netty.versions.properties")        => MergeStrategy.first
  case PathList("META-INF", "versions", _ @ _*)                    => MergeStrategy.discard
  case PathList("META-INF", "native-image", _ @ _*)                => MergeStrategy.discard
  case PathList("module-info.class")                               => MergeStrategy.discard
  case PathList("reference.conf")                                  => MergeStrategy.concat
  case PathList("application.conf")                                => MergeStrategy.concat
  case PathList("LICENSE") | PathList("LICENSE.txt")               => MergeStrategy.discard
  case PathList("NOTICE")  | PathList("NOTICE.txt")                => MergeStrategy.discard

  // JAXB clash: prefer one; we exclude javax above, but be safe
  case PathList("javax",   "xml", "bind", _ @ _*)                  => MergeStrategy.first
  case PathList("jakarta", "xml", "bind", _ @ _*)                  => MergeStrategy.first

  // Proto / misc duplicates
  case p if p.endsWith(".proto")                                   => MergeStrategy.first
  case _                                                           => MergeStrategy.first
}
