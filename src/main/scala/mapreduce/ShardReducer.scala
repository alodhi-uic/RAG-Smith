import org.apache.hadoop.io._
import org.apache.hadoop.mapreduce.Reducer
import java.nio.file.{Files, Paths}
import io.circe.parser._
import org.apache.lucene.index.IndexWriter

class ShardReducer extends Reducer[IntWritable, Text, Text, Text]:
  override def reduce(key: IntWritable, values: java.lang.Iterable[Text],
                      ctx: Reducer[LongWritable,Text,Text,Text]#Context): Unit =
    val shard   = key.get
    val baseOut = "out/lucene-index"
    val local   = Files.createDirectories(Paths.get(s"$baseOut/index_shard_$shard"))

    LuceneShardWriter.withWriter(local) { (iw: IndexWriter) =>
      val iter = values.iterator()
      while iter.hasNext do
        val json = iter.next().toString
        val rec  = parse(json).toOption.get.hcursor
        val docId  = rec.get[String]("doc_id").toOption.get
        val chunk  = rec.get[Int]("chunk_id").toOption.get
        val text   = rec.get[String]("text").toOption.get
        val vecArr = rec.get[Vector[Float]]("vec").toOption.get.toArray
        LuceneShardWriter.addChunk(iw, docId, chunk, text, vecArr)
    }
