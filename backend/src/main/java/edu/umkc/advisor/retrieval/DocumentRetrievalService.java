package edu.umkc.advisor.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DocumentRetrievalService {
    private static final int RRF_K = 60;

    private final EmbeddingService embedder;
    private final VectorStore store;
    private final int topK;
    private final double minScore;

    public DocumentRetrievalService(EmbeddingService embedder, VectorStore store, int topK, double minScore) {
        this.embedder = embedder; this.store = store; this.topK = topK; this.minScore = minScore;
    }

    /**
     * Hybrid retrieval: dense cosine search fused with keyword full-text search (RRF).
     * A chunk is kept if its vector score meets {@code minScore} <em>or</em> it was a keyword hit,
     * so exact terms like SBIR or UM-16C are not dropped solely because MiniLM scored them low.
     */
    public List<ScoredChunk> retrieve(String question) {
        if (question == null || question.isBlank()) return List.of();
        int pool = Math.max(topK * 2, topK);
        List<ScoredChunk> dense = store.search(embedder.embed(question), pool);
        List<ScoredChunk> sparse = store.searchKeyword(question, pool);
        return fuse(dense, sparse, topK, minScore);
    }

    static List<ScoredChunk> fuse(List<ScoredChunk> dense, List<ScoredChunk> sparse, int topK, double minScore) {
        Map<String, ScoredChunk> byId = new LinkedHashMap<>();
        Map<String, Double> rrf = new LinkedHashMap<>();
        Set<String> keywordHits = new HashSet<>();
        addRank(dense, byId, rrf, null);
        addRank(sparse, byId, rrf, keywordHits);
        List<ScoredChunk> ranked = new ArrayList<>(byId.values());
        ranked.sort(Comparator.comparingDouble((ScoredChunk c) -> rrf.getOrDefault(c.chunk().id(), 0.0)).reversed());
        List<ScoredChunk> out = new ArrayList<>();
        for (ScoredChunk c : ranked) {
            boolean keyword = keywordHits.contains(c.chunk().id());
            boolean denseOk = c.score() >= minScore && denseContains(dense, c.chunk().id());
            if (!keyword && !denseOk) continue;
            out.add(c);
            if (out.size() >= topK) break;
        }
        return out;
    }

    private static boolean denseContains(List<ScoredChunk> dense, String id) {
        for (ScoredChunk c : dense) if (c.chunk().id().equals(id)) return true;
        return false;
    }

    private static void addRank(List<ScoredChunk> hits, Map<String, ScoredChunk> byId, Map<String, Double> rrf,
                                Set<String> mark) {
        for (int i = 0; i < hits.size(); i++) {
            ScoredChunk c = hits.get(i);
            String id = c.chunk().id();
            byId.putIfAbsent(id, c);
            rrf.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            if (mark != null) mark.add(id);
        }
    }

    public List<SourceRecord> sources() { return store.listSources(); }
}
