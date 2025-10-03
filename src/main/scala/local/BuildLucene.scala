//package local
//
//import java.nio.file.{Files, Paths}
//import scala.io.Source
//import scala.util.Using
//
//import config.Settings
//import util.{Pdfs, Chunker, OllamaClient, Vectors}
//import org.apache.lucene.analysis.standard.StandardAnalyzer
//import org.apache.lucene.document.{Document, StringField, TextField, Field, StoredField}
//import org.apache.lucene.store.FSDirectory
//import org.apache.lucene.index.{IndexWriter, IndexWriterConfig, VectorSimilarityFunction}
//import org.apache.lucene.document.KnnFloatVectorField
//import org.slf4j.LoggerFactory
//
//object BuildLucene {
//  private val log = LoggerFactory.getLogger(getClass)
//
//  // --- simple PDF text cleaner ---
//  private val urlRe    = "(?i)\\bhttps?://\\S+\\b".r
//  private val noiseRes = List(
//    "(?i)available online at".r,
//    "(?i)all rights reserved".r,
//    "(?i)permission to make digital".r,
//    "(?i)copyright".r,
//    "(?i)doi\\s*:".r,
//    "(?i)arxiv".r
//  )
//
//  private def clean(text: String): String = {
//    val lines = text.linesIterator.map(_.trim).filter(_.nonEmpty).toVector
//    val freq  = lines.groupBy(identity).view.mapValues(_.size).toMap
//    lines
//      .filter(l => freq(l) <= 2) // drop repeating headers/footers
//      .filterNot(l => urlRe.findFirstIn(l).nonEmpty || noiseRes.exists(_.findFirstIn(l).nonEmpty))
//      .mkString("\n")
//  }
//
//  def main(args: Array[String]): Unit = {
//    val cfg    = Settings
//    val client = new OllamaClient(cfg.ollamaHost)
//    val local = Paths.get(cfg.outputDir, "index_shard_00")
//    Files.createDirectories(local)
//
//    val iwc = new IndexWriterConfig(new StandardAnalyzer())
//    val iw  = new IndexWriter(FSDirectory.open(local), iwc)
//
//    val lines = Using.resource(Source.fromFile(cfg.inputList))(_.getLines().toVector).take(3) // small subset
//    val minChunkChars = 80 // skip tiny/blank chunks
//
//    val sim = cfg.similarity.toLowerCase match {
//      case "cosine" => VectorSimilarityFunction.COSINE
//      case "dot"    => VectorSimilarityFunction.DOT_PRODUCT
//      case _        => VectorSimilarityFunction.EUCLIDEAN
//    }
//
//    lines.foreach { p =>
//      val path  = Paths.get(p.trim)
//      val docId = path.getFileName.toString
//      val text  = clean(Pdfs.readText(path))
//
//      // Filter out blank/tiny chunks
//      val rawChunks = Chunker.split(text, cfg.window, cfg.overlap)
//      val chunks = rawChunks.map(_.trim).filter(_.length >= minChunkChars)
//      if (chunks.isEmpty) {
//        log.warn(s"Skipping $docId: no usable chunks after filtering")
//      } else {
//        // Batch-embed
//        val vecs = chunks.grouped(cfg.embedBatch max 1).toVector.flatMap { g =>
//          client.embed(g.toVector, cfg.embedModel)
//        }.toVector
//
//        if (vecs.size != chunks.size) {
//          log.warn(s"Embedding count mismatch for $docId: chunks=${chunks.size} vectors=${vecs.size}")
//        }
//
//        // Index only pairs with a non-empty vector
//        chunks.zipAll(vecs, "", Array.emptyFloatArray).zipWithIndex.foreach {
//          case ((c, v), idx) =>
//            val vec = Option(v).getOrElse(Array.emptyFloatArray)
//            if (c.nonEmpty && vec.nonEmpty) {
//              val finalVec =
//                if (cfg.similarity.equalsIgnoreCase("cosine")) Vectors.l2Normalize(vec) else vec
//              val d = new Document()
//              d.add(new StringField("doc_id",  docId, Field.Store.YES))
//              d.add(new StringField("chunk_id", idx.toString, Field.Store.YES))
//              d.add(new StoredField("path", path.toString))                // store source path for debugging
//              d.add(new TextField(cfg.textField, c, Field.Store.YES))     // text field name from config
//              d.add(new KnnFloatVectorField(cfg.vecField, finalVec, sim)) // vector field name from config
//              iw.addDocument(d)
//            } else {
//              log.warn(s"Skipping empty entry for $docId chunk#$idx (textLen=${c.length}, vecLen=${vec.length})")
//            }
//        }
//      }
//    }
//
//    iw.commit(); iw.close()
//    println(s"Local index created at: $local")
//  }
//}


package local

import java.nio.file.{Files, Paths}
import scala.io.Source
import scala.util.Using

import config.Settings
import util.{Pdfs, Chunker, OllamaClient, Vectors}

import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.{Document, StringField, TextField, Field}
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.index.{IndexWriter, IndexWriterConfig}
import org.apache.lucene.index.IndexWriterConfig.OpenMode
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.document.KnnFloatVectorField
import org.apache.lucene.index.DirectoryReader
import org.slf4j.LoggerFactory

object BuildLucene {
  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val cfg    = Settings
    // WRITE DIRECTLY INTO output.dir/index_shard_00
    val outDir = Paths.get(cfg.outputDir, "index_shard_00")
    Files.createDirectories(outDir)

    val iwc = new IndexWriterConfig(new StandardAnalyzer())
      .setOpenMode(OpenMode.CREATE) // always (re)create
    val iw  = new IndexWriter(FSDirectory.open(outDir), iwc)

    val client = new OllamaClient(cfg.ollamaHost)

    // read all files from list.txt (one path per line)
    val lines = Using.resource(Source.fromFile(cfg.inputList))(_.getLines().toVector)
    val minChunkChars = 40 // skip tiny/blank chunks

    val sim = cfg.similarity.toLowerCase match {
      case "cosine" => VectorSimilarityFunction.COSINE
      case "dot"    => VectorSimilarityFunction.DOT_PRODUCT
      case _        => VectorSimilarityFunction.EUCLIDEAN
    }

    var totalDocs = 0
    lines.foreach { raw =>
      val p = raw.trim
      if (p.nonEmpty) {
        val pathStr = p
        val path    = Paths.get(pathStr)
        if (!Files.exists(path)) {
          log.warn(s"Skipping missing file: $pathStr")
        } else {
          val docId = path.getFileName.toString
          val text  = Pdfs.readText(path)

          // chunk & filter
          val rawChunks = Chunker.split(text, cfg.window, cfg.overlap)
          val chunks    = rawChunks.map(_.trim).filter(_.length >= minChunkChars)

          log.info(s"File=$docId textLen=${text.length} rawChunks=${rawChunks.size} keptChunks=${chunks.size}")

          if (chunks.nonEmpty) {
            // embed in batches
            val vecs = chunks.grouped(cfg.embedBatch max 1).toVector.flatMap { g =>
              client.embed(g.toVector, cfg.embedModel)
            }.toVector

            if (vecs.size != chunks.size) {
              log.warn(s"Embedding count mismatch for $docId: chunks=${chunks.size} vectors=${vecs.size}")
            }

            // index chunk/vector pairs
            chunks.zipAll(vecs, "", Array.emptyFloatArray).zipWithIndex.foreach {
              case ((c, v), idx) =>
                val vec = Option(v).getOrElse(Array.emptyFloatArray)
                if (c.nonEmpty && vec.nonEmpty) {
                  val finalVec =
                    if (cfg.similarity.equalsIgnoreCase("cosine")) Vectors.l2Normalize(vec) else vec
                  val d = new Document()
                  d.add(new StringField("doc_id",   docId,       Field.Store.YES))
                  d.add(new StringField("chunk_id", idx.toString, Field.Store.YES))
                  d.add(new TextField(cfg.textField, c, Field.Store.YES))
                  d.add(new KnnFloatVectorField(cfg.vecField, finalVec, sim))
                  iw.addDocument(d)
                  totalDocs += 1
                }
            }
          }
        }
      }
    }

    iw.commit(); iw.close()

    // quick sanity check
    val r = DirectoryReader.open(FSDirectory.open(outDir))
    try {
      println(s"Lucene shard created at: $outDir")
      println(s"maxDoc=${r.maxDoc()} (docs added this run=$totalDocs)")
    } finally r.close()
  }
}
