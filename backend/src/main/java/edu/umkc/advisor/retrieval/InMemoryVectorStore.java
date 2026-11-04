package edu.umkc.advisor.retrieval;

import java.util.*;

/** Exact cosine search. Used in tests and usable as a no-database fallback for demos. */
public final class InMemoryVectorStore implements VectorStore {
    private final Map<String, SourceRecord> sources = new LinkedHashMap<>();
    private final Map<String, List<Chunk>> chunks = new HashMap<>();
    private final Map<String, List<float[]>> vectors = new HashMap<>();

    @Override public synchronized void replaceSource(SourceRecord s, List<Chunk> c, List<float[]> e) {
        if (c.size() != e.size()) throw new IllegalArgumentException("chunks/embeddings size mismatch");
        sources.put(s.id(), s); chunks.put(s.id(), List.copyOf(c)); vectors.put(s.id(), List.copyOf(e));
    }

    @Override public synchronized List<ScoredChunk> search(float[] q, int k) {
        List<ScoredChunk> all = new ArrayList<>();
        for (SourceRecord s : sources.values()) {
            if (!s.enabled()) continue;
            List<Chunk> cs = chunks.get(s.id()); List<float[]> vs = vectors.get(s.id());
            for (int i = 0; i < cs.size(); i++) all.add(new ScoredChunk(cs.get(i), s, Vectors.dot(q, vs.get(i))));
        }
        all.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return all.subList(0, Math.min(k, all.size()));
    }

    @Override public synchronized List<SourceRecord> listSources() { return new ArrayList<>(sources.values()); }

    @Override public synchronized void setEnabled(String id, boolean enabled) {
        SourceRecord s = sources.get(id);
        if (s != null) sources.put(id, new SourceRecord(s.id(), s.title(), s.url(), s.type(), s.contentHash(), enabled));
    }

    @Override public synchronized void deleteSource(String id) { sources.remove(id); chunks.remove(id); vectors.remove(id); }
}
