import com.typesafe.config.ConfigFactory

@main def hello(): Unit =
  val c = ConfigFactory.load()
  val pdfDir = c.getConfig("rag").getString("pdfDir")
  println(s"RAG-441 boot OK. pdfDir = $pdfDir")
