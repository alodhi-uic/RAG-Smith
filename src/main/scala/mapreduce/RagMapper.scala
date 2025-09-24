import org.apache.hadoop.io._
import org.apache.hadoop.mapreduce.Mapper

class RagMapper extends Mapper[LongWritable, Text, IntWritable, Text]:
  override def map(key: LongWritable, v: Text, ctx: Mapper[LongWritable,Text,IntWritable,Text]#Context): Unit =
    // TODO: real extract → chunk → embed → emit JSON; stub for compile:
    val shard = 0
    val json  = """{"doc_id":"demo","chunk_id":0,"text":"stub","vec":[0.1,0.2]}"""
    ctx.write(new IntWritable(shard), new Text(json))
