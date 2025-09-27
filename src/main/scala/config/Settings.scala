package config

import com.typesafe.config.ConfigFactory
import org.apache.lucene.index.VectorSimilarityFunction

object Settings {
  private val conf   = ConfigFactory.load()
  private val ragCfg = conf.getConfig("rag")

  // IO
  val inputList: String = ragCfg.getString("input.list")
  val outputDir: String = ragCfg.getString("output.dir")

  // Sharding
  val shards: Int = ragCfg.getInt("shards")

  // Chunking
  val window : Int = ragCfg.getInt("chunk.windowChars")
  val overlap: Int = ragCfg.getInt("chunk.overlapChars")

  // Embeddings / Ollama
  val embedModel : String = ragCfg.getString("embed.model")
  val similarity : String = ragCfg.getString("embed.similarity") // "cosine" | "dot" | "l2"
  val embedBatch : Int    = ragCfg.getInt("embed.batchSize")
  val ollamaHost : String = ragCfg.getString("ollama.host")

  // Local scratch
  val tmpDir: String = ragCfg.getString("local.tmpDir")

  // Lucene field names (what your code expects)
  val textField: String = ragCfg.getString("index.textField")
  val vecField : String = ragCfg.getString("index.fieldName")

  // Back-compat aliases (safe to keep; remove later if unused)
  val indexTextField: String = textField
  val indexVecField : String = vecField

  // Convenience: Lucene similarity enum derived from config
  val luceneSim: VectorSimilarityFunction = similarity.toLowerCase match {
    case "cosine" => VectorSimilarityFunction.COSINE
    case "dot"    => VectorSimilarityFunction.DOT_PRODUCT
    case _        => VectorSimilarityFunction.EUCLIDEAN // treat anything else as L2
  }
}
