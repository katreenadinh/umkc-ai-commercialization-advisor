package edu.umkc.advisor.retrieval;

import java.util.Set;

/** Deterministic bag-of-words embedder for tests only. Not a semantic model. */
public final class HashingEmbedder implements EmbeddingService {
    private static final Set<String> STOP = Set.of("the", "a", "an", "is", "are", "of", "to", "and", "in", "what", "how", "do", "i", "my", "for", "on", "it");
    @Override public int dimensions() { return 256; }
    @Override public float[] embed(String text) {
        float[] v = new float[256];
        for (String w : text.toLowerCase().split("[^a-z0-9]+")) {
            if (w.length() < 2 || STOP.contains(w)) continue;
            int h = w.hashCode();
            v[Math.floorMod(h, 256)] += (h & 0x10000) == 0 ? 1f : -1f;
        }
        return Vectors.normalize(v);
    }
}
