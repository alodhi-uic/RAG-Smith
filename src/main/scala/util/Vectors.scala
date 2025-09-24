object Vectors:
  def l2Normalize(x: Array[Float]): Array[Float] =
    val n = math.sqrt(x.map(v => v*v).sum.toDouble)
    if n == 0.0 then x else x.map(v => (v / n).toFloat)
