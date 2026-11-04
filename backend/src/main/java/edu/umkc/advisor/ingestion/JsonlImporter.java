package edu.umkc.advisor.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.*;

/**
 * Imports the notebook's chunks.jsonl. One JSON object per line:
 * {source_id, title, url, source_type, section, page, chunk_index, text}.
 * Embeddings in the file are ignored: Java re-embeds so index and query vectors always come from the same model.
 */
public final class JsonlImporter {
    private final ObjectMapper mapper = new ObjectMapper();
    private final IngestionPipeline pipeline;

    public JsonlImporter(IngestionPipeline pipeline) { this.pipeline = pipeline; }

    public List<IngestionPipeline.Report> importJsonl(Reader reader) throws IOException {
        Map<String, List<Segment>> segs = new LinkedHashMap<>();
        Map<String, String[]> meta = new HashMap<>();
        try (BufferedReader br = new BufferedReader(reader)) {
            String line; int n = 0;
            while ((line = br.readLine()) != null) {
                n++;
                if (line.isBlank()) continue;
                JsonNode j = mapper.readTree(line);
                String sid = req(j, "source_id", n), title = req(j, "title", n), text = req(j, "text", n);
                segs.computeIfAbsent(sid, k -> new ArrayList<>()).add(new Segment(
                        j.path("section").isNull() ? null : j.path("section").asText(null),
                        j.hasNonNull("page") ? j.get("page").asInt() : null, text));
                meta.putIfAbsent(sid, new String[]{title, j.path("url").asText(null), j.path("source_type").asText("JSONL")});
            }
        }
        List<IngestionPipeline.Report> reports = new ArrayList<>();
        for (var e : segs.entrySet()) {
            String[] m = meta.get(e.getKey());
            reports.add(pipeline.ingest(toUuid(e.getKey()), new ExtractedDocument(m[0], e.getValue()), m[1], m[2]));
        }
        return reports;
    }

    private static String req(JsonNode j, String f, int line) {
        String v = j.path(f).asText("");
        if (v.isBlank()) throw new IllegalArgumentException("Line " + line + ": missing field '" + f + "'");
        return v;
    }

    /** Stable UUID so re-importing the same source_id replaces rather than duplicates. */
    static String toUuid(String sourceId) {
        try { return UUID.fromString(sourceId).toString(); }
        catch (IllegalArgumentException e) { return UUID.nameUUIDFromBytes(sourceId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(); }
    }
}
