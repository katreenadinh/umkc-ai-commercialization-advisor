package edu.umkc.advisor.retrieval;

import java.util.List;

public interface VectorStore {
    /** Atomically replaces all chunks of a source (create or update). */
    void replaceSource(SourceRecord source, List<Chunk> chunks, List<float[]> embeddings);
    List<ScoredChunk> search(float[] queryEmbedding, int k);
    List<SourceRecord> listSources();
    void setEnabled(String sourceId, boolean enabled);
    void deleteSource(String sourceId);
}
