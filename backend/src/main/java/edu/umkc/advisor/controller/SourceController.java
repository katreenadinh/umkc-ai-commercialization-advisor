package edu.umkc.advisor.controller;

import edu.umkc.advisor.config.AdvisorProperties;
import edu.umkc.advisor.ingestion.IngestionPipeline;
import edu.umkc.advisor.ingestion.JsonlImporter;
import edu.umkc.advisor.retrieval.DocumentRetrievalService;
import edu.umkc.advisor.retrieval.ScoredChunk;
import edu.umkc.advisor.retrieval.SourceRecord;
import edu.umkc.advisor.retrieval.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SourceController {
    private final DocumentRetrievalService retrieval;
    private final VectorStore store;
    private final JsonlImporter importer;
    private final String adminToken;

    public SourceController(DocumentRetrievalService retrieval, VectorStore store, JsonlImporter importer, AdvisorProperties props) {
        this.retrieval = retrieval; this.store = store; this.importer = importer; this.adminToken = props.adminToken();
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

    // ---- admin (disabled unless ADMIN_TOKEN is set) ----
    @PostMapping("/admin/sources/import")
    public ResponseEntity<?> importJsonl(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                         @RequestParam("file") MultipartFile file) throws IOException {
        if (!authorized(token)) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required."));
        try (var reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            List<IngestionPipeline.Report> reports = importer.importJsonl(reader);
            return ResponseEntity.ok(reports);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/admin/sources/{id}/enabled")
    public ResponseEntity<?> setEnabled(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                        @PathVariable String id, @RequestParam boolean value) {
        if (!authorized(token)) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required."));
        store.setEnabled(id, value);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/admin/sources/{id}")
    public ResponseEntity<?> delete(@RequestHeader(value = "X-Admin-Token", required = false) String token, @PathVariable String id) {
        if (!authorized(token)) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required."));
        store.deleteSource(id);
        return ResponseEntity.noContent().build();
    }

    private boolean authorized(String token) {
        if (adminToken == null || adminToken.isBlank() || token == null) return false;
        return MessageDigest.isEqual(adminToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }
}
