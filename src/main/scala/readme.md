# CS441 HW1 — RAG @ Scale (Step‑By‑Step plan + starter scaffold)

> **Goal (Option 1 / Hadoop+EMR default):** Build a distributed pipeline that reads hundreds of MSR PDFs → extracts/cleans text → chunks → embeds with **Ollama** → writes a **Lucene (HNSW) vector index** shard‑per‑reducer → query fan‑out/fan‑in → deploy on **AWS EMR**. Also emit CSV/YAML stats (vocab + frequencies, nearest neighbors, word-sim/analogy toy evals).
> **Alternative (Option 2 / CORBA):** Same functionality with omniORB objects instead of MapReduce. Notes at the end.

---

## 0) What you’ll deliver (TL;DR)

* **Code** (Scala 3 + SBT) that runs locally and on EMR, producing **Lucene index shards** and **CSV/YAML statistics**.
* **README.md** with install, config, run instructions, and a short discussion of results/limitations.
* **Unit/Integration tests** (≥5) using ScalaTest.
* **Logging** via Logback/SLF4J with multiple levels.
* **Screen‑capture video** of EMR run, with your intro and AWS UI visible.

---

## 1) Prerequisites (versions that work well)

* **JDK:** 21 (8–24 allowed in brief, but pick 17 or 21 for comfort)
* **Scala:** 3.5.1
* **SBT:** 1.10.x
* **IntelliJ IDEA + Scala plugin**
* **Git + GitHub account** (private repo; grant TA/instructor access)
* **AWS account** + **AWS CLI v2** (configured `aws configure`)
* **Hadoop** (only if you want to test MR locally; EMR bundles it)
* **Ollama** running locally or on a VM: set `export OLLAMA_HOST=http://127.0.0.1:11434`

    * Pull models you’ll use, e.g. `mxbai-embed-large` and `llama3.1:8b` (or alternates)

> **Tip:** Use a Python venv only for ad‑hoc analysis notebooks if you like; main deliverable is **Scala**.

---

## 2) Repo layout (suggested)

```
cs441-hw1/
  ├─ project/
  │   └─ build.properties
  ├─ build.sbt
  ├─ .gitignore
  ├─ README.md
  ├─ conf/
  │   ├─ application.conf
  │   └─ logback.xml
  ├─ data/
  │   └─ msr_pdfs/              # (optional local copy or S3 paths in conf)
  ├─ src/
  │   ├─ main/
  │   │   └─ scala/
  │   │       └─ edu/uic/cs441/hw1/
  │   │            ├─ Main.scala
  │   │            ├─ config/Settings.scala
  │   │            ├─ io/Pdfs.scala
  │   │            ├─ text/Chunker.scala
  │   │            ├─ llm/OllamaClient.scala
  │   │            ├─ index/LuceneShardWriter.scala
  │   │            ├─ mapreduce/RagMapper.scala
  │   │            ├─ mapreduce/ShardReducer.scala
  │   │            ├─ query/AskLucene.scala
  │   │            └─ util/Vectors.scala
  │   └─ test/
  │       └─ scala/
  │           └─ edu/uic/cs441/hw1/
  │               ├─ ChunkerSpec.scala
  │               ├─ OllamaClientSpec.scala
  │               ├─ LuceneShardWriterSpec.scala
  │               ├─ VocabStatsSpec.scala
  │               └─ EndToEndLocalSpec.scala
  └─ scripts/
      ├─ run_local.sh
      ├─ package_jar.sh
      └─ emr_submit_examples.md
```

---

## 3) SBT & dependencies

**build.sbt**

```scala
ThisBuild / scalaVersion := "3.5.1"
ThisBuild / organization := "edu.uic.cs441"
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
  // Logging
  "ch.qos.logback" % "logback-classic" % "1.5.6",
  "org.slf4j" % "slf4j-api" % "2.0.16",
  // Hadoop MapReduce API (compile against; EMR provides runtime)
  "org.apache.hadoop" % "hadoop-common" % "3.3.6" % Provided,
  "org.apache.hadoop" % "hadoop-mapreduce-client-core" % "3.3.6" % Provided,
  // Tests
  "org.scalatest" %% "scalatest" % "3.2.19" % Test
)

Compile / run / fork := true
Compile / javaOptions ++= Seq("-Xmx6G", "-XX:+UseG1GC")
Test / parallelExecution := false

// Optional FAISS JNI (Linux CPU only)
// libraryDependencies += "com.criteo.jfaiss" % "jfaiss-cpu" % "1.7.0-1"
```

**.gitignore (excerpt)**

```
/target/
/.bsp/
/.idea/
/.metals/
/.vscode/
/.DS_Store
conf/*.local.*
```

---

## 4) Configuration & logging

**conf/application.conf**

```hocon
rag {
  pdfDir = "data/msr_pdfs"          # or s3://bucket/MSRCorpus
  outDir = "out/lucene-index"        # or s3://bucket/index/
  statsOut = "out/stats"             # CSV/YAML files

  chunk {
    maxChars = 1600
    overlap  = 240
  }

  embeddings {
    model = "mxbai-embed-large"
    similarity = "cosine"            # cosine | dot | l2
  }

  query {
    topK = 8
    rerankTopK = 0                    # set >0 if you add a mini-reranker later
  }

  mapreduce {
    shards = 8
  }
}

ollama {
  baseUrl = ${?OLLAMA_HOST}
  baseUrl = "http://127.0.0.1:11434"
}
```

**conf/logback.xml**

```xml
<configuration>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>
  <root level="INFO">
    <appender-ref ref="STDOUT"/>
  </root>
  <logger name="edu.uic.cs441.hw1" level="INFO"/>
</configuration>
```

---

## 5) Core code stubs (ready to fill)

**Settings.scala** (Typesafe config loader)

```scala
package edu.uic.cs441.hw1.config

import com.typesafe.config.ConfigFactory

final case class ChunkCfg(maxChars: Int, overlap: Int)

final case class EmbCfg(model: String, similarity: String)

final case class RagCfg(pdfDir: String, outDir: String, statsOut: String, chunk: ChunkCfg, embeddings: EmbCfg, mapreduceShards: Int, topK: Int)

object Settings {
  private val c = ConfigFactory.load()
  private val r = c.getConfig("rag")
  val pdfDir = r.getString("pdfDir")
  val outDir = r.getString("outDir")
  val statsOut = r.getString("statsOut")
  val chunk = ChunkCfg(r.getConfig("chunk").getInt("maxChars"), r.getConfig("chunk").getInt("overlap"))
  val emb = EmbCfg(r.getConfig("embeddings").getString("model"), r.getConfig("embeddings").getString("similarity"))
  val shards = r.getConfig("Personal/mapreduce").getInt("shards")
  val topK = r.getConfig("Personal/mapreduce/query").getInt("topK")
  val ollamaBase = c.getConfig("ollama").getString("baseUrl")
}
```

**Pdfs.scala** (PDF → text)

```scala
package edu.uic.cs441.hw1.io

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.file.{Files, Path}
import java.io.ByteArrayInputStream

object Pdfs {
  def readText(p: Path): String = {
    val bytes = Files.readAllBytes(p)
    val doc   = PDDocument.load(new ByteArrayInputStream(bytes))
    try new PDFTextStripper().getText(doc) finally doc.close()
  }
}
```

**Chunker.scala** (clean + sliding windows)

```scala
package edu.uic.cs441.hw1.text

object Chunker {
  def normalize(s: String): String = s.replaceAll("\\s+"," ").trim

  def split(s: String, maxChars: Int, overlap: Int): Vector[String] = {
    val clean = normalize(s)
    val out   = Vector.newBuilder[String]
    var i = 0
    while (i < clean.length) {
      val end   = Math.min(i + maxChars, clean.length)
      val slice = clean.substring(i, end)
      val cut   = slice.lastIndexWhere(ch => ch == '.' || ch == '\n')
      val piece = if (cut >= (maxChars * 0.6)) slice.substring(0, cut + 1) else slice
      out += piece
      i += Math.max(piece.length - overlap, 1)
    }
    out.result()
  }
}
```

**OllamaClient.scala** (embeddings + chat)

```scala
package edu.uic.cs441.hw1.llm

import sttp.client3._
import sttp.client3.circe._
import io.circe._, io.circe.generic.semiauto._

final case class EmbedReq(model: String, input: Vector[String])
final case class EmbedResp(embeddings: Vector[Vector[Float]])
object EmbedResp {
  given Decoder[EmbedResp] = Decoder.instance { c =>
    c.downField("embeddings").as[Vector[Vector[Float]]].map(EmbedResp.apply)
      .orElse(c.downField("embedding").as[Vector[Float]].map(v => EmbedResp(Vector(v))))
  }
}

final case class ChatMessage(role: String, content: String)
final case class ChatReq(model: String, messages: Vector[ChatMessage], stream: Boolean = false)
final case class ChatMsg(role: String, content: String)
final case class ChatResp(message: ChatMsg)
object ChatResp {
  given Decoder[ChatMsg]  = deriveDecoder
  given Decoder[ChatResp] = deriveDecoder
}

class OllamaClient(base: String) {
  private val be   = HttpClientSyncBackend()
  private val eurl = uri"${base}/api/embeddings"
  private val curl = uri"${base}/api/chat"

  def embed(texts: Vector[String], model: String): Vector[Array[Float]] = {
    val req = basicRequest.post(eurl).body(EmbedReq(model, texts)).response(asJson[EmbedResp])
    req.send(be).body.fold(throw _, _.embeddings.map(_.toArray))
  }

  def chat(messages: Vector[ChatMessage], model: String): String = {
    val req = basicRequest.post(curl).body(ChatReq(model, messages)).response(asJson[ChatResp])
    req.send(be).body.fold(throw _, _.message.content)
  }
}
```

**Vectors.scala** (norms & similarity)

```scala
package edu.uic.cs441.hw1.util

object Vectors {
  def l2Normalize(x: Array[Float]): Array[Float] = {
    val n = Math.sqrt(x.map(v => v*v).sum.toDouble)
    if (n == 0.0) x else x.map(v => (v / n).toFloat)
  }
}
```

**LuceneShardWriter.scala**

```scala
package edu.uic.cs441.hw1.index

import org.apache.lucene.document._
import org.apache.lucene.index._
import org.apache.lucene.store._
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.search.knn.KnnFloatVectorField
import org.apache.lucene.search.similarities._
import org.apache.lucene.search._

object LuceneShardWriter {
  def withWriter(dir: java.nio.file.Path)(f: IndexWriter => Unit): Unit = {
    val iwc = new IndexWriterConfig(new StandardAnalyzer())
    val iw = new IndexWriter(FSDirectory.open(dir), iwc)
    try f(iw) finally {
      iw.commit();
      iw.close()
    }
  }

  def addChunk(iw: IndexWriter, docId: String, chunkId: Int, text: String, vec: Array[Float]): Unit = {
    val d = new Document()
    d.add(new StringField("doc_id", docId, Field.Store.YES))
    d.add(new StringField("chunk_id", chunkId.toString, Field.Store.YES))
    d.add(new TextField("Personal/mapreduce/text", text, Field.Store.YES))
    d.add(new KnnFloatVectorField("vec", vec))
    iw.addDocument(d)
  }
}
```

**RagMapper.scala** (Hadoop Mapper)

```scala
package edu.uic.cs441.hw1.mapreduce

import org.apache.hadoop.io._
import org.apache.hadoop.mapreduce.Mapper
import java.nio.file.Paths
import edu.uic.cs441.hw1.io.Pdfs
import edu.uic.cs441.hw1.text.Chunker
import edu.uic.cs441.hw1.llm.OllamaClient
import edu.uic.cs441.hw1.util.Vectors

class RagMapper extends Mapper[LongWritable, Text, IntWritable, Text] {
  private val client = new OllamaClient(sys.env.getOrElse("OLLAMA_HOST","http://127.0.0.1:11434"))

  override def map(_: LongWritable, v: Text, ctx: Mapper[LongWritable,Text,IntWritable,Text]#Context): Unit = {
    val path   = Paths.get(v.toString)
    val docId  = path.getFileName.toString
    val text   = Pdfs.readText(path)
    val chunks = Chunker.split(text, ctx.getConfiguration.getInt("chunk.maxChars", 1600), ctx.getConfiguration.getInt("chunk.overlap", 240))
    val model  = ctx.getConfiguration.get("emb.model","mxbai-embed-large")
    val vecs   = client.embed(chunks, model).map(Vectors.l2Normalize)
    val shardN = ctx.getNumReduceTasks
    val shard  = Math.abs(docId.hashCode) % Math.max(shardN, 1)

    chunks.zip(vecs).zipWithIndex.foreach { case ((c, e), id) =>
      val rec = s"""{"doc_id":"$docId","chunk_id":$id,"text":${encode(c)},"vec":[${e.mkString(",")}] }"""
      ctx.write(new IntWritable(shard), new Text(rec))
    }
  }

  private def encode(s: String) = "\"" + s.replace("\\","\\\\").replace("\"","\\\"") + "\""
}
```

**ShardReducer.scala** (Hadoop Reducer → shard dir)

```scala
package edu.uic.cs441.hw1.mapreduce

import org.apache.hadoop.io._
import org.apache.hadoop.mapreduce.Reducer
import java.nio.file.{Files, Paths}
import edu.uic.cs441.hw1.index.LuceneShardWriter
import org.apache.lucene.document.Document
import org.apache.lucene.document.{StringField, Field, TextField}
import org.apache.lucene.index.IndexWriter
import io.circe.parser._

class ShardReducer extends Reducer[IntWritable, Text, Text, Text] {
  override def reduce(key: IntWritable, values: java.lang.Iterable[Text], ctx: Reducer[IntWritable, Text, Text, Text]#Context): Unit = {
    val shard = key.get
    val baseOut = ctx.getConfiguration.get("out.dir", "out/lucene-index")
    val local = Files.createDirectories(Paths.get(s"$baseOut/index_shard_$shard"))

    LuceneShardWriter.withWriter(local) { iw: IndexWriter =>
      val it = values.iterator()
      while (it.hasNext) {
        val json = it.next().toString
        val rec = parse(json).toOption.get.hcursor
        val docId = rec.get[String]("doc_id").toOption.get
        val chunk = rec.get[Int]("chunk_id").toOption.get
        val text = rec.get[String]("Personal/mapreduce/text").toOption.get
        val vecArr = rec.get[Vector[Float]]("vec").toOption.get.toArray
        LuceneShardWriter.addChunk(iw, docId, chunk, text, vecArr)
      }
    }
  }
}
```

**AskLucene.scala** (fan‑out/fan‑in query, local)

```scala
package edu.uic.cs441.hw1.query

import org.apache.lucene.store.FSDirectory
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.search._
import org.apache.lucene.search.knn.KnnFloatVectorQuery
import org.apache.lucene.document.Document
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

object AskLucene {
  final case class Hit(docId: String, chunkId: Int, text: String, score: Float)

  def searchAllShards(indexRoot: Path, queryVec: Array[Float], topK: Int): Vector[Hit] = {
    val shardDirs = Files.list(indexRoot).iterator().asScala.toVector.filter(_.getFileName.toString.startsWith("index_shard_"))
    val perShard = Math.max(topK * 2, topK)
    val heap = scala.collection.mutable.PriorityQueue.empty[Hit](Ordering.by(_.score))

    shardDirs.foreach { dir =>
      val reader = DirectoryReader.open(FSDirectory.open(dir))
      val search = new IndexSearcher(reader)
      val q = new KnnFloatVectorQuery("vec", queryVec, perShard)
      val hits = search.search(q, perShard).scoreDocs
      hits.foreach { sd =>
        val d: Document = search.doc(sd.doc)
        heap.enqueue(Hit(d.get("doc_id"), d.get("chunk_id").toInt, d.get("Personal/mapreduce/text"), sd.score))
      }
      reader.close()
    }
    heap.dequeueAll.reverse.take(topK).toVector
  }
}
```

**Main.scala** (local monolith entrypoints)

```scala
package edu.uic.cs441.hw1

import java.nio.file.{Files, Paths}
import scala.jdk.StreamConverters.*
import edu.uic.cs441.hw1.io.Pdfs
import edu.uic.cs441.hw1.text.Chunker
import edu.uic.cs441.hw1.llm.OllamaClient
import edu.uic.cs441.hw1.index.LuceneShardWriter
import edu.uic.cs441.hw1.util.Vectors

object Main {
  def main(args: Array[String]): Unit = {
    val pdfDir  = Paths.get("data/msr_pdfs")
    val outDir  = Paths.get("out/lucene-index/index_shard_0")
    val client  = new OllamaClient(sys.env.getOrElse("OLLAMA_HOST","http://127.0.0.1:11434"))

    val paths = Files.list(pdfDir).toScala(Seq).filter(p => p.toString.toLowerCase.endsWith(".pdf")).take(3) // small smoke test
    LuceneShardWriter.withWriter(outDir) { iw =>
      paths.foreach { p =>
        val text   = Pdfs.readText(p)
        val chunks = Chunker.split(text, 1600, 240)
        val vecs   = client.embed(chunks, "mxbai-embed-large").map(Vectors.l2Normalize)
        chunks.zip(vecs).zipWithIndex.foreach { case ((c,e),i) =>
          LuceneShardWriter.addChunk(iw, p.getFileName.toString, i, c, e)
        }
      }
    }
    println(s"Wrote index to: $outDir")
  }
}
```

---

## 6) Tests (examples to reach ≥5)

* **ChunkerSpec:** windowing counts for L=10k chars with W=1,600/O=240.
* **OllamaClientSpec:** round‑trip single string → returns vector length > 0 (mock or integration flag).
* **LuceneShardWriterSpec:** index then query Knn with the same vector returns your doc.
* **VocabStatsSpec:** small toy corpus produces expected counts and nearest neighbors.
* **EndToEndLocalSpec:** 1–2 PDFs → build → search → non‑empty hits.

---

## 7) Local run

```
sbt clean compile test
sbt "run"
```

For Hadoop local mode, package a fat JAR and run with `hadoop jar ...` (or rely on EMR packaging below).

---

## 8) EMR run sketch (Option 1)

1. **Build fat jar** (use sbt-assembly if desired) → `target/scala-3.5.1/cs441-hw1-assembly.jar`.
2. **Upload inputs**: PDFs → `s3://your-bucket/MSRCorpus/` ; conf JSON/args as needed.
3. **Create EMR cluster** (EMR 6.x w/ Hadoop + Spark OK; you use Hadoop MR only):

    * Instance type: m5.xlarge (CPU) is fine for reducers; **embedding** calls go out to your Ollama host (see #9).
4. **Submit step**: Hadoop Jar step → Main class of your MR driver (you can add a tiny `Driver.scala` if you prefer instead of `Main.scala`).
5. Reducers each write `index_shard_*` dirs to S3 output path.
6. Download shards (or keep in S3) for serving.

> **Note:** For speed, at serve‑time copy shards from S3 to local NVMe/EBS, then open Lucene.

---

## 9) Where does Ollama run?

* **Local dev:** on your laptop, `OLLAMA_HOST=http://127.0.0.1:11434`.
* **Cloud:** run **Ollama** on a small EC2 (CPU ok for small models) inside the same VPC as EMR. Set a **Security Group** rule so EMR nodes can POST to Ollama. Configure `OLLAMA_HOST` in EMR step env or pass via Hadoop job conf. Avoid exposing Ollama to the public internet.

---

## 10) Stats output (CSV/YAML)

* **vocab.csv**: `token,token_id,count`
* **neighbors.csv**: `token,neighbor,cosine`
* **eval\_analogy.csv** and **eval\_similarity.csv**: toy sets and scores.

Write these from a small **Stats.scala** job that reuses the index or intermediate text.

---

## 11) README pointers (avoid point losses)

* Exact **install** and **run** steps (local + EMR), config tables, how you **partitioned** data, inputs/outputs.
* Screenshots of EMR steps + link to your **YouTube** video.
* Log levels and samples, and a **brief design rationale** (mapper/reducer responsibilities, shard policy, why window sizes, etc.).

---

## 12) Option 2 (CORBA) quick notes

* Replace Mapper/Reducer with omniORB objects: **Extractor**, **Embedder**, **Indexer** as remotely callable services; a **Coordinator** partitions by `abs(hash(doc_id)) % R` and dispatches.
* Same on‑disk artifacts (Lucene shards + CSV/YAML stats). Deployment on EC2 instead of EMR. Keep logs + tests.

---

## 13) Next actions

1. Clone repo and paste these files.
2. `sbt clean compile test` (fix any local path issues).
3. Run `Main.scala` on 1–3 PDFs (smoke test).
4. Package assembly, stage a tiny EMR job, record a short run.
5. Expand to the full corpus and collect stats.
