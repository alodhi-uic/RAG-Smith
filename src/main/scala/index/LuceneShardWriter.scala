import org.apache.lucene.document._
import org.apache.lucene.index._
import org.apache.lucene.store._
import org.apache.lucene.analysis.standard.StandardAnalyzer

object LuceneShardWriter:
  def withWriter(dir: java.nio.file.Path)(f: IndexWriter => Unit): Unit =
    val iwc = new IndexWriterConfig(new StandardAnalyzer())
    val iw  = new IndexWriter(FSDirectory.open(dir), iwc)
    try f(iw) finally { iw.commit(); iw.close() }

  def addChunk(iw: IndexWriter, docId: String, chunkId: Int, text: String, vec: Array[Float]): Unit =
    val d = Document()
    d.add(StringField("doc_id", docId, Field.Store.YES))
    d.add(StringField("chunk_id", chunkId.toString, Field.Store.YES))
    d.add(TextField("text", text, Field.Store.YES))
    d.add(KnnFloatVectorField("vec", vec))   // from org.apache.lucene.document
    iw.addDocument(d)
