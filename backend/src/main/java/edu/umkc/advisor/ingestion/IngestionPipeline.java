package edu.umkc.advisor.ingestion;

import edu.umkc.advisor.retrieval.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Extracted document -> chunks -> embeddings -> vector store. Runs outside the request path. */
public final class IngestionPipeline {
    public record Report(String sourceId, String title, int chars, int chunks, List<String> warnings) {}

    private final Chunker chunker;
    private final EmbeddingService embedder;
    private final VectorStore store;

    public IngestionPipeline(Chunker chunker, EmbeddingService embedder, VectorStore store) {
        this.chunker = chunker; this.embedder = embedder; this.store = store;
    }

    /** @param sourceId stable id so re-ingesting a source replaces it; pass null to derive one from the URL/title. */
    public Report ingest(String sourceId, ExtractedDocument doc, String url, String type) {
        List<String> warnings = new ArrayList<>();
        if (doc.totalChars() == 0) warnings.add("No text extracted (scanned PDF or wrong content selector?). Source NOT stored.");
        else if (doc.totalChars() < 500) warnings.add("Very little text extracted (" + doc.totalChars() + " chars). Inspect before trusting.");
        String id = sourceId != null ? sourceId
                : UUID.nameUUIDFromBytes((url != null ? url : doc.title()).getBytes(StandardCharsets.UTF_8)).toString();
        if (doc.totalChars() == 0) return new Report(id, doc.title(), 0, 0, warnings);

        List<Chunk> chunks = chunker.chunk(id, doc.segments());
        List<float[]> vectors = new ArrayList<>();
        for (Chunk c : chunks) vectors.add(embedder.embed(embedText(doc.title(), c)));
        StringBuilder all = new StringBuilder();
        doc.segments().forEach(s -> all.append(s.text()));
        store.replaceSource(new SourceRecord(id, doc.title(), url, type, sha256(all.toString()), true), chunks, vectors);
        return new Report(id, doc.title(), doc.totalChars(), chunks.size(), warnings);
    }

    /** Title and section prefix improve retrieval; the stored chunk text stays unmodified. */
    public static String embedText(String title, Chunk c) {
        return title + (c.section() != null ? " | " + c.section() : "") + "\n" + c.text();
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
