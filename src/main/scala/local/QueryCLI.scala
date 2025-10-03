package local

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Try

import config.Settings
import util.{OllamaClient, Vectors}

import org.apache.lucene.document.Document
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.search.{IndexSearcher, KnnFloatVectorQuery, ScoreDoc, TopDocs, TotalHits}
import org.apache.lucene.store.FSDirectory

object QueryCLI {

  final case class Args(
                         query: String,
                         dir: Option[String] = None,
                         k: Int = 10,
                         debug: Boolean = false
                       )

  private case class Hit(score: Float, readerIdx: Int, docId: Int)
  private implicit val minHeapOrdering: Ordering[Hit] =
    Ordering.by[Hit, Float](-_.score) // min-heap via max-heap on -score

  def main(raw: Array[String]): Unit = {
    parseArgs(raw) match {
      case None =>
        println(
          """Usage:
            |  runMain local.QueryCLI [--dir=/path/to/output] [--k=10] [--debug] "your query text"
            |
            |Example:
            |  sbt 'runMain local.QueryCLI --dir=/Users/me/cloud/output --k=5 "attention is all you need"'
            |""".stripMargin
        )

      case Some(args) =>
        val cfg = Settings

        // ----- Build query vector via Ollama -----
        val client = new OllamaClient(cfg.ollamaHost)
        val rawVec = client.embed(Vector(args.query), cfg.embedModel).headOption.getOrElse(Array.emptyFloatArray)
        val qVec   = if (cfg.similarity.equalsIgnoreCase("cosine")) Vectors.l2Normalize(rawVec) else rawVec

        if (args.debug) {
          println(s"[debug] cfg.outputDir=${cfg.outputDir}")
          println(s"[debug] cfg.vecField=${cfg.vecField}  cfg.textField=${cfg.textField}")
          println(s"[debug] similarity=${cfg.similarity}  embed.model=${cfg.embedModel}")
          println(s"[debug] query embedding dim = ${qVec.length}")
        }

        if (qVec.isEmpty) {
          println("Query embedding is empty. Check your embed model/host.")
          sys.exit(1)
        }

        // ----- Find shard directories (folders that look like Lucene indexes) -----
        val base: Path = Paths.get(args.dir.getOrElse(cfg.outputDir))
        val shardDirs: Vector[Path] =
          if (Files.isDirectory(base)) {
            Files.list(base).iterator().asScala.toVector
              .filter(Files.isDirectory(_))
              .filter { d =>
                val names = Try(Files.list(d).iterator().asScala.map(_.getFileName.toString).toSet).getOrElse(Set.empty)
                names.exists(_.startsWith("segments"))
              }
          } else Vector.empty

        if (args.debug) {
          if (shardDirs.isEmpty) println(s"[debug] No shard-like dirs under: $base")
          else {
            println(s"[debug] Candidate shards under $base:")
            shardDirs.foreach(p => println(s"  - $p"))
          }
        }

        if (shardDirs.isEmpty) {
          println(s"No shards found under: $base")
          sys.exit(1)
        }

        // ----- Open each shard -----
        val opened: Vector[(Path, DirectoryReader)] = shardDirs.flatMap { p =>
          try {
            val r = DirectoryReader.open(FSDirectory.open(p))
            if (args.debug) println(s"[debug] opened $p (maxDoc=${r.maxDoc()})")
            Some(p -> r)
          } catch {
            case t: Throwable =>
              if (args.debug) println(s"[debug] skipping $p (${t.getClass.getSimpleName}: ${t.getMessage})")
              None
          }
        }

        if (opened.isEmpty) {
          println(s"Failed to open any Lucene shard under: $base")
          sys.exit(1)
        }

        // ----- Search each shard separately and merge -----
        val k = math.max(args.k, 1)
        val perShardK = math.max(k, math.min(256, k * 3)) // oversample per shard

        val heap = new scala.collection.mutable.PriorityQueue[Hit]()(minHeapOrdering)
        val searchers = opened.map { case (_, r) => new IndexSearcher(r) }.toArray

        try {
          opened.indices.foreach { i =>
            val s = searchers(i)
            val q = new KnnFloatVectorQuery(cfg.vecField, qVec, perShardK)

            val top: TopDocs =
              try s.search(q, perShardK)
              catch {
                case iae: IllegalArgumentException if iae.getMessage != null && iae.getMessage.toLowerCase.contains("dimension") =>
                  println(s"Warning: vector dimension mismatch on shard #${i + 1}: ${iae.getMessage}")
                  new TopDocs(new TotalHits(0, TotalHits.Relation.EQUAL_TO), Array.empty[ScoreDoc])
                case t: Throwable =>
                  if (args.debug) println(s"[debug] shard#${i + 1}: search error: ${t.getClass.getSimpleName}: ${t.getMessage}")
                  new TopDocs(new TotalHits(0, TotalHits.Relation.EQUAL_TO), Array.empty[ScoreDoc])
              }

            if (args.debug) println(s"[debug] shard#${i + 1}: returned ${Option(top.scoreDocs).map(_.length).getOrElse(0)} hits")

            if (top.scoreDocs != null) {
              top.scoreDocs.foreach { sd =>
                heap.enqueue(Hit(sd.score, i, sd.doc))
                if (heap.size > k) heap.dequeue()
              }
            }
          }

          if (heap.isEmpty) {
            println("No results.")
          } else {
            val merged = heap.dequeueAll.reverse
            println(s"Top ${merged.length} results:")
            merged.zipWithIndex.foreach { case (h, rank) =>
              val d = searchers(h.readerIdx).doc(h.docId)
              printHit(rank, h.score, d, cfg.textField)
            }
          }
        } finally {
          opened.foreach { case (_, r) => Try(r.close()) }
        }
    }
  }

  private def printHit(rank: Int, score: Float, d: Document, textFieldName: String): Unit = {
    val docId   = Option(d.get("doc_id"))
    val chunkId = Option(d.get("chunk_id"))
    val path    = Option(d.get("path"))
    val chunk   = Option(d.get("chunk"))

    val idLabel =
      path.map(p => s"path=$p${chunk.fold("")(c => s" chunk=$c")}").orElse {
        docId.map(id => s"doc_id=$id${chunkId.fold("")(c => s" chunk_id=$c")}")
      }.getOrElse("doc=?")

    val snippet =
      Option(d.get(textFieldName))
        .orElse(Option(d.get("text")))
        .map(_.take(240).replaceAll("\\s+", " "))
        .getOrElse("")

    println(f" #${rank + 1}%02d  score=$score%.4f  $idLabel")
    if (snippet.nonEmpty) println(s"      $snippet")
  }

  // ----------------- very small arg parser -----------------
  private def parseArgs(args: Array[String]): Option[Args] = {
    if (args.isEmpty) return None

    var k: Int = 10
    var dir: Option[String] = None
    var debug = false
    val tail = scala.collection.mutable.ArrayBuffer.empty[String]

    val it = args.iterator
    while (it.hasNext) {
      it.next() match {
        case s if s == "--debug" => debug = true
        case s if s.startsWith("--k=") =>
          k = Try(s.stripPrefix("--k=").toInt).getOrElse(10)
        case "--k" if it.hasNext =>
          k = Try(it.next().toInt).getOrElse(10)
        case s if s.startsWith("--dir=") =>
          dir = Some(s.stripPrefix("--dir="))
        case "--dir" if it.hasNext =>
          dir = Some(it.next())
        case other =>
          tail += other
      }
    }

    val query = tail.mkString(" ").trim
    if (query.isEmpty) None else Some(Args(query, dir, k, debug))
  }
}
