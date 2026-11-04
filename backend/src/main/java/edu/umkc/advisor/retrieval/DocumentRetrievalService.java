package edu.umkc.advisor.retrieval;

import java.util.List;

public final class DocumentRetrievalService {
    private final EmbeddingService embedder;
    private final VectorStore store;
    private final int topK;
    private final double minScore;

    public DocumentRetrievalService(EmbeddingService embedder, VectorStore store, int topK, double minScore) {
        this.embedder = embedder; this.store = store; this.topK = topK; this.minScore = minScore;
    }

    /** Returns passages at or above the similarity threshold, best first. Empty means "no supporting sources". */
    public List<ScoredChunk> retrieve(String question) {
        return store.search(embedder.embed(question), topK).stream().filter(c -> c.score() >= minScore).toList();
    }

    public List<SourceRecord> sources() { return store.listSources(); }
}
