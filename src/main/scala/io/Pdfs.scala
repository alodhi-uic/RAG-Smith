import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.file.{Files, Path}
import java.io.ByteArrayInputStream

object Pdfs:
  def readText(p: Path): String =
    val bytes = Files.readAllBytes(p)
    val doc   = PDDocument.load(new ByteArrayInputStream(bytes))
    try PDFTextStripper().getText(doc) finally doc.close()
