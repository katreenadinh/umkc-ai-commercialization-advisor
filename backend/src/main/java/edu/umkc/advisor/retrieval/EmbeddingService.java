package edu.umkc.advisor.retrieval;

/** Text to unit-length vector. Production: all-MiniLM-L6-v2 (384 dims). Tests use a hashing fake. */
public interface EmbeddingService {
    float[] embed(String text);
    int dimensions();
}
