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
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.document.KnnFloatVectorField

object BuildLucene {
  def main(args: Array[String]): Unit = {
    val cfg = Settings.rag
    val client = new util.OllamaClient(cfg.ollamaHost)
    val local  = Paths.get(cfg.tmpDir, "lucene-local")
    Files.createDirectories(local)

    val iwc = new IndexWriterConfig(new StandardAnalyzer())
    val iw  = new IndexWriter(FSDirectory.open(local), iwc)

    val lines = Using.resource(Source.fromFile(cfg.inputList))(_.getLines().toVector).take(3) // small subset
    lines.foreach { p =>
      val path  = Paths.get(p.trim)
      val docId = path.getFileName.toString
      val text  = Pdfs.readText(path)
      val chunks= Chunker.split(text, cfg.window, cfg.overlap)

      val vecs  = chunks.grouped(cfg.embedBatch).toVector.flatMap { g =>
        client.embed(g.toVector, cfg.embedModel)
      }.toVector

      val sim = cfg.similarity.toLowerCase match {
        case "cosine" => VectorSimilarityFunction.COSINE
        case "dot"    => VectorSimilarityFunction.DOT_PRODUCT
        case _        => VectorSimilarityFunction.EUCLIDEAN
      }

      chunks.zip(vecs).zipWithIndex.foreach { case ((c, v), idx) =>
        val vec = if (cfg.similarity.equalsIgnoreCase("cosine")) Vectors.l2Normalize(v) else v
        val d = new Document()
        d.add(new StringField("doc_id", docId, Field.Store.YES))
        d.add(new StringField("chunk_id", idx.toString, Field.Store.YES))
        d.add(new TextField(cfg.textField, c, Field.Store.YES))
        d.add(new KnnFloatVectorField(cfg.vecField, vec, sim))
        iw.addDocument(d)
      }
    }
    iw.commit(); iw.close()
    println(s"Local index created at: $local")
  }
}
