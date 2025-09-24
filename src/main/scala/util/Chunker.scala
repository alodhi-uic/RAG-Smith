package util

object Chunker {
  def normalize(s: String): String =
    s.replaceAll("\\s+", " ").trim

  /** Chunk by ~maxChars, with overlap chars, prefer cutting on sentence end if near the tail. */
  def split(s: String, maxChars: Int, overlap: Int): Vector[String] = {
    val clean = normalize(s)
    val out   = Vector.newBuilder[String]
    var i     = 0
    while (i < clean.length) {
      val end   = Math.min(i + maxChars, clean.length)
      val slice = clean.substring(i, end)
      val cut   = slice.lastIndexWhere(ch => ch == '.' || ch == '\n')
      val piece =
        if (cut >= (maxChars * 0.6).toInt) slice.substring(0, cut + 1)
        else slice
      out += piece
      i += Math.max(1, piece.length - overlap)
    }
    out.result()
  }
}
