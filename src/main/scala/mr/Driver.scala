package mr

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{IntWritable, LongWritable, Text}
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.lib.input.NLineInputFormat
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat

import config.Settings

object Driver {
  def main(args: Array[String]): Unit = {
    val cfg = Settings.rag
    val conf = new Configuration()
    conf.setInt("mapreduce.input.lineinputformat.linespermap", 1) // one PDF path per map task (adjust if needed)

    val job = Job.getInstance(conf, "CS441-HW1-RAG")
    job.setJarByClass(classOf[RagMapper])

    job.setMapperClass(classOf[RagMapper])
    job.setMapOutputKeyClass(classOf[IntWritable])
    job.setMapOutputValueClass(classOf[Text])

    job.setReducerClass(classOf[ShardReducer])
    job.setOutputKeyClass(classOf[Text])
    job.setOutputValueClass(classOf[Text])

    job.setInputFormatClass(classOf[NLineInputFormat]) // each line in input is a PDF absolute path
    job.setOutputFormatClass(classOf[TextOutputFormat[Text,Text]])

    FileInputFormat.addInputPath(job, new Path(cfg.inputList))
    val outPath = new Path(cfg.outputDir)
    FileOutputFormat.setOutputPath(job, outPath)

    // Number of reducers == number of shards
    job.setNumReduceTasks(cfg.shards)

    val ok = job.waitForCompletion(true)
    System.exit(if (ok) 0 else 1)
  }
}
