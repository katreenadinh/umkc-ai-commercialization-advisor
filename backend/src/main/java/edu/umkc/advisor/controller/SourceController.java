package edu.umkc.advisor.controller;

import edu.umkc.advisor.retrieval.DocumentRetrievalService;
import edu.umkc.advisor.retrieval.ScoredChunk;
import edu.umkc.advisor.retrieval.SourceRecord;
import edu.umkc.advisor.retrieval.VectorStore;
import edu.umkc.advisor.safety.ReferralService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SourceController {
    private final DocumentRetrievalService retrieval;
    private final VectorStore store;
    private final ReferralService referral;

    public SourceController(DocumentRetrievalService retrieval, VectorStore store, ReferralService referral) {
        this.retrieval = retrieval; this.store = store; this.referral = referral;
    }

    public record ContactView(String officeName, String email, String url) {}

    public record SourceView(String id, String title, String url, String type, boolean enabled) {}
    public record PassageView(String chunkId, String title, String url, String section, Integer page, String excerpt, double score) {}

    @GetMapping("/contact")
    public ContactView contact() {
        ReferralService.Contact c = referral.contact();
        return new ContactView(c.officeName(), c.email(), c.url());
    }

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
