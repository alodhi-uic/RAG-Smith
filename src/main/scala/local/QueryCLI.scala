package local

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Try

import config.Settings
import util.{OllamaClient, Vectors}

import org.apache.lucene.document.Document
import org.apache.lucene.index.{DirectoryReader, IndexReader, MultiReader}
import org.apache.lucene.search.{IndexSearcher, KnnFloatVectorQuery, ScoreDoc, TopDocs}
import org.apache.lucene.store.FSDirectory

object QueryCLI {

  final case class Args(
                         query: String,
                         dir: Option[String] = None,
                         k: Int = 10,
                         debug: Boolean = false
                       )

  def main(raw: Array[String]): Unit = {
    parseArgs(raw) match {
      case None =>
        println(
          """Usage:
            |  runMain local.QueryCLI [--dir=/path/to/output] [--k=10] [--debug] "your query text"
            |
            |Examples:
            |  sbt 'runMain local.QueryCLI --dir=/Users/me/cloud/output --k=5 "attention is all you need"'
            |""".stripMargin
        )
      case Some(args) =>
        val cfg = Settings

        // ----- Build query vector via Ollama -----
        val client  = new OllamaClient(cfg.ollamaHost)
        val rawVec  = client.embed(Vector(args.query), cfg.embedModel).headOption.getOrElse(Array.emptyFloatArray)
        val qVec    = if (cfg.similarity.equalsIgnoreCase("cosine")) Vectors.l2Normalize(rawVec) else rawVec

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

        // ----- Find shard directories (folders containing Lucene segments) -----
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

        // ----- Open readers -----
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

        val readers: Array[IndexReader] = opened.map(_._2: IndexReader).toArray
        val multiReader  = new MultiReader(readers, /*closeSubReaders=*/true)
        val searcher     = new IndexSearcher(multiReader)

        try {
          // ----- Vector KNN search across all shards -----
          val k = math.max(args.k, 1)
          val q = new KnnFloatVectorQuery(cfg.vecField, qVec, k)
          val top: TopDocs = searcher.search(q, k)

          if (top.scoreDocs == null || top.scoreDocs.isEmpty) {
            println("No results.")
          } else {
            println(s"Top $k results:")
            top.scoreDocs.zipWithIndex.foreach { case (sd, rank) =>
              val doc = searcher.doc(sd.doc)
              printHit(rank, sd, doc, cfg.textField)
            }
          }
        } finally {
          // MultiReader was created with closeSubReaders=true
          multiReader.close()
        }
    }
  }

  private def printHit(rank: Int, sd: ScoreDoc, d: Document, textFieldName: String): Unit = {
    val docId   = Option(d.get("doc_id"))
    val chunkId = Option(d.get("chunk_id"))
    val path    = Option(d.get("path"))
    val chunk   = Option(d.get("chunk"))

    val idLabel =
      path.map(p => s"path=$p${chunk.fold("")(c => s" chunk=$c")}").orElse {
        docId.map(id => s"doc_id=$id${chunkId.fold("")(c => s" chunk_id=$c")}")
      }.getOrElse(s"doc=${sd.doc}")

    val snippet =
      Option(d.get(textFieldName))
        .orElse(Option(d.get("text"))) // fallback if field name differs
        .map(_.take(240).replaceAll("\\s+", " "))
        .getOrElse("")

    println(f" #${rank + 1}%02d  score=${sd.score}%.4f  $idLabel")
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
