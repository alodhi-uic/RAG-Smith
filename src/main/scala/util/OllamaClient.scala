package util

import sttp.client3.*
import sttp.client3.circe.*            // brings circeBodySerializer & asJson into scope
import io.circe.*, io.circe.generic.semiauto.*

final case class EmbedReq(model: String, input: Vector[String])
final case class PromptReq(model: String, prompt: String)
final case class EmbedResp(embeddings: Vector[Vector[Float]])

// ---- Circe codecs ----
given Encoder[EmbedReq]  = deriveEncoder[EmbedReq]
given Encoder[PromptReq] = deriveEncoder[PromptReq]

object EmbedResp {
  given Decoder[EmbedResp] =
    Decoder.instance { c =>
      c.downField("embeddings").as[Vector[Vector[Float]]].map(EmbedResp.apply)
        .orElse(c.downField("embedding").as[Vector[Float]].map(v => EmbedResp(Vector(v))))
    }
}

class OllamaClient(base: String) {
  private val backend = HttpClientSyncBackend()
  private val eurl    = uri"$base/api/embeddings"

  /**
   * Try batch embeddings with "input". If vectors come back empty (some models like bge-m3),
   * fall back to per-text calls using "prompt".
   */
  def embed(texts: Vector[String], model: String): Vector[Array[Float]] = {
    if (texts.isEmpty) return Vector.empty

    // --- First attempt: batch with "input" ---
    val batchReq =
      basicRequest
        .post(eurl)
        .body(EmbedReq(model, texts))
        .response(asJson[EmbedResp])

    batchReq.send(backend).body match {
      case Right(resp) if resp.embeddings.nonEmpty && resp.embeddings.exists(_.nonEmpty) =>
        resp.embeddings.map(_.toArray)

      case _ =>
        // --- Fallback: per-text with "prompt" ---
        texts.map { t =>
          val singleReq =
            basicRequest
              .post(eurl)
              .body(PromptReq(model, t))
              .response(asJson[EmbedResp])

          singleReq.send(backend).body.fold(
            err => throw new RuntimeException(s"Ollama embeddings error (prompt): $err"),
            ok  => ok.embeddings.headOption.getOrElse(Vector.empty).toArray
          )
        }
    }
  }
}
