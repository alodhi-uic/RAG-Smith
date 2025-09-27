package mr

import org.apache.hadoop.io.{IntWritable, LongWritable, Text}
import org.apache.hadoop.mapreduce.Mapper
import java.nio.file.{Paths, Files}

import config.Settings
import util.{Pdfs, Chunker, Vectors, OllamaClient, Json}
import org.slf4j.LoggerFactory

class RagMapper extends Mapper[LongWritable, Text, IntWritable, Text] {

  private val log      = LoggerFactory.getLogger(getClass)
  private val cfg      = config.Settings
  private val client   = new util.OllamaClient(cfg.ollamaHost)
  private val shardKey = new IntWritable()

  override def map(key: LongWritable,
                   value: Text,
                   ctx: Mapper[LongWritable, Text, IntWritable, Text]#Context): Unit = {

    val pdfPath = value.toString.trim
    if (pdfPath.isEmpty) return

    val path = Paths.get(pdfPath)
    if (!Files.exists(path)) {
      log.warn(s"PDF not found: $pdfPath")
      return
    }

    val docId  = path.getFileName.toString
    val text   = Pdfs.readText(path)

    val minChunkChars = 40
    val chunks = Chunker.split(text, cfg.window, cfg.overlap)
      .map(_.trim).filter(_.length >= minChunkChars)

    if (chunks.isEmpty) {
      log.warn(s"Skipping $docId: no usable chunks after filtering")
      return
    }

    val batch = math.max(cfg.embedBatch, 1)
    val vecs  = chunks.grouped(batch).toVector.flatMap { group =>
      client.embed(group.toVector, cfg.embedModel)
    }.toVector

    val normed =
      if (cfg.similarity.equalsIgnoreCase("cosine")) vecs.map(Vectors.l2Normalize)
      else vecs

    val shard = math.abs(docId.hashCode) % cfg.shards
    shardKey.set(shard)

    // Emit only non-empty vectors
    chunks.zipAll(normed, "", Array.emptyFloatArray).zipWithIndex.foreach { case ((c, e), id) =>
      if (c.nonEmpty && e.nonEmpty) {
        val rec =
          s"""{"doc_id":${Json.encodeString(docId)},"chunk_id":$id,"text":${Json.encodeString(c)},"vec":[${e.mkString(",")}]}"""
        ctx.write(shardKey, new Text(rec))
      } else {
        log.warn(s"Skipping empty embedding for $docId chunk#$id (textLen=${c.length}, vecLen=${e.length})")
      }
    }
  }
}
