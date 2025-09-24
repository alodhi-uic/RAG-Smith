package util

import sttp.client3.*
import sttp.client3.circe.*            // brings circeBodySerializer & asJson into scope
import io.circe.*, io.circe.generic.semiauto.*

final case class EmbedReq(model: String, input: Vector[String])
final case class EmbedResp(embeddings: Vector[Vector[Float]])

// ---- Circe codecs ----
given Encoder[EmbedReq] = deriveEncoder[EmbedReq]            // <<== add this
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

  def embed(texts: Vector[String], model: String): Vector[Array[Float]] = {
    val req =
      basicRequest
        .post(eurl)
        .body(EmbedReq(model, texts))       // uses circeBodySerializer[EmbedReq] because Encoder is in scope
        .response(asJson[EmbedResp])        // parse JSON into EmbedResp

    req.send(backend).body.fold(
      err => throw new RuntimeException(s"Ollama embeddings error: $err"),
      ok  => ok.embeddings.map(_.toArray)
    )
  }
}
