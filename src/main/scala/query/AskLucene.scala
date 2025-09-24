import org.apache.lucene.store.FSDirectory
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.search._
import org.apache.lucene.document.Document
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

object AskLucene:
  final case class Hit(docId: String, chunkId: Int, text: String, score: Float)

  def searchAllShards(indexRoot: Path, queryVec: Array[Float], topK: Int): Vector[Hit] =
    val shardDirs = Files.list(indexRoot).iterator().asScala.toVector
      .filter(_.getFileName.toString.startsWith("index_shard_"))
    val perShard = math.max(topK * 2, topK)
    val heap = scala.collection.mutable.PriorityQueue.empty[Hit](Ordering.by(_.score))

    shardDirs.foreach { dir =>
      val reader = DirectoryReader.open(FSDirectory.open(dir))
      val searcher = IndexSearcher(reader)
      val q = KnnFloatVectorQuery("vec", queryVec, perShard)   // org.apache.lucene.search.KnnFloatVectorQuery
      val hits = searcher.search(q, perShard).scoreDocs
      hits.foreach { sd =>
        val d: Document = searcher.doc(sd.doc)
        heap.enqueue(Hit(d.get("doc_id"), d.get("chunk_id").toInt, d.get("text"), sd.score))
      }
      reader.close()
    }
    heap.dequeueAll.reverse.take(topK).toVector
