package edu.umkc.advisor.controller;

import edu.umkc.advisor.retrieval.DocumentRetrievalService;
import edu.umkc.advisor.retrieval.ScoredChunk;
import edu.umkc.advisor.retrieval.SourceRecord;
import edu.umkc.advisor.retrieval.VectorStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SourceController {
    private final DocumentRetrievalService retrieval;
    private final VectorStore store;

    public SourceController(DocumentRetrievalService retrieval, VectorStore store) {
        this.retrieval = retrieval; this.store = store;
    }

    public record SourceView(String id, String title, String url, String type, boolean enabled) {}
    public record PassageView(String chunkId, String title, String url, String section, Integer page, String excerpt, double score) {}

    @GetMapping("/sources")
    public List<SourceView> sources() {
        return store.listSources().stream().map(s -> new SourceView(s.id(), s.title(), s.url(), s.type(), s.enabled())).toList();
    }

    /** Retrieves the most relevant passages for a query (no LLM involved). */
    @GetMapping("/sources/search")
    public ResponseEntity<?> search(@RequestParam("q") String q) {
        if (q == null || q.isBlank() || q.length() > 500) return ResponseEntity.badRequest().body(Map.of("error", "Provide q (1-500 chars)."));
        List<PassageView> out = retrieval.retrieve(q.trim()).stream().map(SourceController::view).toList();
        return ResponseEntity.ok(out);
    }

    private static PassageView view(ScoredChunk h) {
        String t = h.chunk().text();
        return new PassageView(h.chunk().id(), h.source().title(), h.source().url(), h.chunk().section(), h.chunk().page(),
                t.length() > 400 ? t.substring(0, 400) + "…" : t, h.score());
    }

}
