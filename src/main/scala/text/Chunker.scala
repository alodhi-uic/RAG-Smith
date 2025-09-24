object Chunker:
  def normalize(s: String): String = s.replaceAll("\\s+"," ").trim

  def split(s: String, maxChars: Int, overlap: Int): Vector[String] =
    val clean = normalize(s)
    val out   = Vector.newBuilder[String]
    var i = 0
    while i < clean.length do
      val end   = (i + maxChars).min(clean.length)
      val slice = clean.substring(i, end)
      val cut   = slice.lastIndexWhere(ch => ch == '.' || ch == '\n')
      val piece = if cut >= (maxChars * 0.6).toInt then slice.substring(0, cut + 1) else slice
      out += piece
      i += (piece.length - overlap).max(1)
    out.result()
