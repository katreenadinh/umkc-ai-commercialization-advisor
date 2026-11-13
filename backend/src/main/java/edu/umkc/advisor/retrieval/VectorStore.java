package edu.umkc.advisor.retrieval;

import java.util.List;

public interface VectorStore {
    /** Atomically replaces all chunks of a source (create or update). */
    void replaceSource(SourceRecord source, List<Chunk> chunks, List<float[]> embeddings);
    List<ScoredChunk> search(float[] queryEmbedding, int k);
    /** Keyword / full-text hits. Empty if this store has no keyword index. */
    default List<ScoredChunk> searchKeyword(String query, int k) { return List.of(); }
    List<SourceRecord> listSources();
    List<Chunk> listChunks(String sourceId);
    void setEnabled(String sourceId, boolean enabled);
    void deleteSource(String sourceId);
}
