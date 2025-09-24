package util

object Json {
  def encodeString(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"","\\\"") + "\""
}
