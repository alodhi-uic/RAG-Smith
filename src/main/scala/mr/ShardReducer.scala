package mr

import org.apache.hadoop.io.{IntWritable, Text}
import org.apache.hadoop.mapreduce.Reducer

import java.nio.file.{Files, Paths}
import java.util.UUID

import io.circe.parser.*
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.{Document, StringField, Field, TextField}
import org.apache.lucene.index.{IndexWriter, IndexWriterConfig}
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.document.KnnFloatVectorField

import scala.jdk.CollectionConverters.*

import config.Settings

class ShardReducer extends Reducer[IntWritable, Text, Text, Text] {
  private val cfg = Settings.rag

  override def reduce(key: IntWritable, values: java.lang.Iterable[Text],
                      ctx: Reducer[IntWritable,Text,Text,Text]#Context): Unit = {
    val shardId = key.get
    val local   = Paths.get(cfg.tmpDir, s"lucene-shard-$shardId-${UUID.randomUUID().toString.take(8)}")
    Files.createDirectories(local)

    val iwc = new IndexWriterConfig(new StandardAnalyzer())
    val iw  = new IndexWriter(FSDirectory.open(local), iwc)

    values.asScala.foreach { t =>
      val rec  = parse(t.toString).toOption.get.hcursor
      val doc  = new Document()
      val docId= rec.get[String]("doc_id").toOption.get
      val cid  = rec.get[Int]("chunk_id").toOption.get
      val text = rec.get[String]("Personal/mapreduce/text").toOption.get
      val vec  = rec.get[Vector[Float]]("vec").toOption.get.toArray

      doc.add(new StringField("doc_id",   docId, Field.Store.YES))
      doc.add(new StringField("chunk_id", cid.toString, Field.Store.YES))
      doc.add(new TextField(cfg.textField, text, Field.Store.YES))

      val sim = cfg.similarity.toLowerCase() match {
        case "cosine" => VectorSimilarityFunction.COSINE
        case "dot"    => VectorSimilarityFunction.DOT_PRODUCT
        case _        => VectorSimilarityFunction.EUCLIDEAN
      }
      doc.add(new KnnFloatVectorField(cfg.vecField, vec, sim))
      iw.addDocument(doc)
    }
    iw.commit(); iw.close()

    // Copy local index dir -> HDFS/S3 via Hadoop fs (Reducer's output path)
    // The MR "output" key/value can be a manifest; real copying typically uses FileSystem API.
    // For simplicity, we emit a line that your post-step can use to collect all shard dirs.
    val marker = s"index_shard_$shardId\t$local"
    ctx.write(new Text("SHARD"), new Text(marker))
  }
}
