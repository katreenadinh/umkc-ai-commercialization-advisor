package edu.umkc.advisor.retrieval;

import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;

/** In-process all-MiniLM-L6-v2 (384 dims). No text leaves the machine. */
public class MiniLmEmbeddingService implements EmbeddingService {
    private final AllMiniLmL6V2EmbeddingModel model = new AllMiniLmL6V2EmbeddingModel();

    @Override public float[] embed(String text) { return Vectors.normalize(model.embed(text).content().vector()); }
    @Override public int dimensions() { return 384; }
}
