package mr

import org.apache.hadoop.io.{IntWritable, Text, NullWritable}
import org.apache.hadoop.mapreduce.Reducer

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._

import org.apache.lucene.store.FSDirectory
import org.apache.lucene.index.{IndexWriter, IndexWriterConfig}
import org.apache.lucene.document.{Document, Field, TextField, StoredField, KnnFloatVectorField}

import config.Settings

// Circe: decode the mapper's JSON lines
import io.circe.*
import io.circe.parser.*
import io.circe.generic.semiauto.*

final case class OutRec(path: String, chunk: Int, text: String, vec: Vector[Float])
given Decoder[OutRec] = deriveDecoder[OutRec]

class ShardReducer
  extends Reducer[IntWritable, Text, NullWritable, NullWritable] {

  override def reduce(
                       shardId: IntWritable,
                       values: java.lang.Iterable[Text],
                       context: Reducer[IntWritable, Text, NullWritable, NullWritable]#Context
                     ): Unit = {

    val it = values.iterator()
    if (!it.hasNext) return // nothing to do for this shard

    // /.../output/index_shard_00, 01, ...
    val shardDir = Paths.get(Settings.outputDir, f"index_shard_${shardId.get}%02d")
    Files.createDirectories(shardDir)

    val writer = new IndexWriter(FSDirectory.open(shardDir), new IndexWriterConfig())

    try {
      it.asScala.foreach { t =>
        decode[OutRec](t.toString) match {
          case Right(rec) if rec.vec.nonEmpty =>
            val doc = new Document()
            doc.add(new StoredField("path",  rec.path))
            doc.add(new StoredField("chunk", rec.chunk))
            doc.add(new TextField(Settings.indexTextField, rec.text, Field.Store.NO))
            // Two-arg ctor to avoid needing a Settings.luceneSim symbol:
            doc.add(new KnnFloatVectorField(Settings.indexVecField, rec.vec.toArray))
            writer.addDocument(doc)

          case Right(_) =>
            System.err.println("[ShardReducer] Skipped record with empty vector.")

          case Left(err) =>
            System.err.println(s"[ShardReducer] Bad JSON record skipped: $err")
        }
      }
      writer.commit()
    } finally {
      writer.close()
    }
  }
}
