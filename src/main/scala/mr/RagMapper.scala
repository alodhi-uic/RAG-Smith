package mr

import org.apache.hadoop.io.{IntWritable, LongWritable, Text}
import org.apache.hadoop.mapreduce.Mapper
import java.nio.file.{Paths, Files}

import config.Settings
import util.{Pdfs, Chunker, Vectors, OllamaClient, Json}


class RagMapper extends Mapper[LongWritable, Text, IntWritable, Text] {

  private val cfg      = Settings.rag
  private val client   = OllamaClient(cfg.ollamaHost)
  private val shardKey = new IntWritable()

  // IMPORTANT in Scala 3: include the '=' (procedure syntax is removed)
  override def map(key: LongWritable,
                   value: Text,
                   ctx: Mapper[LongWritable, Text, IntWritable, Text]#Context): Unit = {

    val pdfPath = value.toString.trim
    if (pdfPath.isEmpty) return

    val path = Paths.get(pdfPath)
    if (!Files.exists(path)) return

    val docId  = path.getFileName.toString
    val text   = Pdfs.readText(path)
    val chunks = Chunker.split(text, cfg.window, cfg.overlap)

    val batch = math.max(cfg.embedBatch, 1)
    val vecs  = chunks.grouped(batch).toVector.flatMap { group =>
      client.embed(group.toVector, cfg.embedModel)
    }.toVector

    val normed =
      if (cfg.similarity.equalsIgnoreCase("cosine")) vecs.map(Vectors.l2Normalize)
      else vecs

    val shard = math.abs(docId.hashCode) % cfg.shards
    shardKey.set(shard)

    var id = 0
    chunks.zip(normed).foreach { case (c, e) =>
      val rec =
        s"""{"doc_id":${Json.encodeString(docId)},"chunk_id":$id,"text":${Json.encodeString(c)},"vec":[${e.mkString(",")}]}"""
      ctx.write(shardKey, new Text(rec))
      id += 1
    }
  }
}
