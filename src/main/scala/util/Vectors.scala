package util

object Vectors {
  def l2Normalize(v: Array[Float]): Array[Float] = {
    val norm = Math.sqrt(v.foldLeft(0.0)((a,b) => a + b*b)).toFloat
    if (norm == 0f) v else v.map(_ / norm)
  }
}
