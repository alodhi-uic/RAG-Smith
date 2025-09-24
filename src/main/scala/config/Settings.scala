import com.typesafe.config.ConfigFactory

final case class ChunkCfg(maxChars: Int, overlap: Int)
final case class EmbCfg(model: String, similarity: String)

object Settings:
  private val c = ConfigFactory.load()
  private val r = c.getConfig("rag")

  val pdfDir   : String   = r.getString("pdfDir")
  val outDir   : String   = r.getString("outDir")
  val statsOut : String   = r.getString("statsOut")
  val chunk    : ChunkCfg = ChunkCfg(
    r.getConfig("chunk").getInt("maxChars"),
    r.getConfig("chunk").getInt("overlap")
  )
  val emb      : EmbCfg   = EmbCfg(
    r.getConfig("embeddings").getString("model"),
    r.getConfig("embeddings").getString("similarity")
  )
  val shards   : Int      = r.getConfig("mapreduce").getInt("shards")
  val topK     : Int      = r.getConfig("query").getInt("topK")
  val ollamaBase: String  = c.getConfig("ollama").getString("baseUrl")
