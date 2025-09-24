package config

import com.typesafe.config.ConfigFactory

final case class RagSettings(
                              inputList: String,
                              outputDir: String,
                              shards: Int,
                              window: Int,
                              overlap: Int,
                              embedModel: String,
                              similarity: String,
                              embedBatch: Int,
                              ollamaHost: String,
                              statsEnable: Boolean,
                              statsOutCsv: String,
                              statsOutYaml: String,
                              statsSampleNN: Int,
                              tmpDir: String,
                              vecField: String,
                              textField: String
                            )

object Settings {
  private val c = ConfigFactory.load().getConfig("rag")
  val rag: RagSettings = RagSettings(
    inputList    = c.getString("input.list"),
    outputDir    = c.getString("output.dir"),
    shards       = c.getInt("shards"),
    window       = c.getInt("chunk.windowChars"),
    overlap      = c.getInt("chunk.overlapChars"),
    embedModel   = c.getString("embed.model"),
    similarity   = c.getString("embed.similarity"),
    embedBatch   = c.getInt("embed.batchSize"),
    ollamaHost   = sys.env.getOrElse("OLLAMA_HOST", c.getString("ollama.host")),
    statsEnable  = c.getBoolean("stats.enable"),
    statsOutCsv  = c.getString("stats.outCsv"),
    statsOutYaml = c.getString("stats.outYaml"),
    statsSampleNN= c.getInt("stats.sampleNN"),
    tmpDir       = c.getString("local.tmpDir"),
    vecField     = c.getString("index.fieldName"),
    textField    = c.getString("index.textField")
  )
}
