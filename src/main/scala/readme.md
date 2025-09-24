# CS441 HW1 — RAG @ Scale (Step‑By‑Step plan + starter scaffold)

> **Goal (Option 1 / Hadoop+EMR default):**
> Build a distributed pipeline that reads hundreds of MSR PDFs → extracts/cleans text → chunks → embeds with **Ollama** → writes a **Lucene (HNSW) vector index** shard‑per‑reducer → query fan‑out/fan‑in → deploy on **AWS EMR**. Also emit CSV/YAML stats (vocab + frequencies, nearest neighbors, word-sim/analogy toy evals).
---
