package llm

import sttp.client3._
import sttp.client3.circe._            // enables .response(asJson[..])
import io.circe._
import io.circe.generic.semiauto._     // we derive encoders/decoders explicitly

// ---------- request/response models ----------
final case class EmbedReq(model: String, input: Vector[String])
final case class EmbedResp(embeddings: Vector[Vector[Float]])
object EmbedResp:
  given Decoder[EmbedResp] =
    Decoder.instance { c =>
      c.downField("embeddings").as[Vector[Vector[Float]]].map(EmbedResp.apply)
        .orElse(c.downField("embedding").as[Vector[Float]].map(v => EmbedResp(Vector(v))))
    }

final case class ChatMessage(role: String, content: String)
final case class ChatReq(model: String, messages: Vector[ChatMessage], stream: Boolean = false)
final case class ChatMsg(role: String, content: String)
final case class ChatResp(message: ChatMsg)
object ChatResp:
  given Decoder[ChatMsg]  = deriveDecoder
  given Decoder[ChatResp] = deriveDecoder

// ---------- encoders for request bodies ----------
object JsonEncoders:
  given Encoder[EmbedReq]     = deriveEncoder
  given Encoder[ChatMessage]  = deriveEncoder
  given Encoder[ChatReq]      = deriveEncoder

// ---------- client ----------
class OllamaClient(base: String):
  // IMPORTANT in Scala 3: import givens explicitly
  import JsonEncoders.given                 // brings Encoder[EmbedReq], Encoder[ChatReq], Encoder[ChatMessage]
  import sttp.client3.circe.given           // brings the given BodySerializer for any Circe-encodable type

  private val backend = HttpClientSyncBackend()
  private val eurl    = uri"$base/api/embeddings"
  private val curl    = uri"$base/api/chat"

  def embed(texts: Vector[String], model: String): Vector[Array[Float]] =
    val req = basicRequest
      .post(eurl)
      .body(EmbedReq(model, texts))         // uses Encoder[EmbedReq] + BodySerializer given
      .response(asJson[EmbedResp])
    req.send(backend).body.fold(throw _, _.embeddings.map(_.toArray))

  def chat(messages: Vector[ChatMessage], model: String): String =
    val req = basicRequest
      .post(curl)
      .body(ChatReq(model, messages))       // uses Encoder[ChatReq] + BodySerializer given
      .response(asJson[ChatResp])
    req.send(backend).body.fold(throw _, _.message.content)
