package edu.umkc.advisor.controller;

import edu.umkc.advisor.retrieval.Chunk;
import edu.umkc.advisor.retrieval.DocumentRetrievalService;
import edu.umkc.advisor.retrieval.ScoredChunk;
import edu.umkc.advisor.retrieval.SourceFileLocator;
import edu.umkc.advisor.retrieval.SourceRecord;
import edu.umkc.advisor.retrieval.VectorStore;
import edu.umkc.advisor.safety.ReferralService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class SourceController {
    private static final MediaType DOCX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final DocumentRetrievalService retrieval;
    private final VectorStore store;
    private final ReferralService referral;
    private final SourceFileLocator files;

    public SourceController(DocumentRetrievalService retrieval, VectorStore store, ReferralService referral,
                            SourceFileLocator files) {
        this.retrieval = retrieval; this.store = store; this.referral = referral; this.files = files;
    }

    public record ContactView(String officeName, String email, String url) {}

    public record SourceView(String id, String title, String url, String type, boolean enabled, String fileName) {}
    public record PassageView(String chunkId, String title, String url, String section, Integer page, String excerpt, double score) {}

    @GetMapping("/contact")
    public ContactView contact() {
        ReferralService.Contact c = referral.contact();
        return new ContactView(c.officeName(), c.email(), c.url());
    }

    @GetMapping("/sources")
    public List<SourceView> sources() {
        return store.listSources().stream().map(this::view).toList();
    }

    /** Retrieves the most relevant passages for a query (no LLM involved). */
    @GetMapping("/sources/search")
    public ResponseEntity<?> search(@RequestParam("q") String q) {
        if (q == null || q.isBlank() || q.length() > 500) return ResponseEntity.badRequest().body(Map.of("error", "Provide q (1-500 chars)."));
        List<PassageView> out = retrieval.retrieve(q.trim()).stream().map(SourceController::passage).toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping("/sources/{id}/text")
    public ResponseEntity<?> text(@PathVariable String id) {
        SourceRecord source = find(id);
        if (source == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Source not found."));
        String text = store.listChunks(id).stream()
                .map(Chunk::text)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining("\n\n"));
        return ResponseEntity.ok(Map.of("title", source.title(), "text", text));
    }

    @GetMapping("/sources/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable String id) throws IOException {
        SourceRecord source = find(id);
        if (source == null) return ResponseEntity.notFound().build();
        Path path = files.fileFor(source).orElse(null);
        if (path == null || !Files.isRegularFile(path)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(mediaType(path))
                .contentLength(Files.size(path))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + path.getFileName() + "\"")
                .body(new FileSystemResource(path));
    }

    private SourceView view(SourceRecord s) {
        Path path = files.fileFor(s).orElse(null);
        return new SourceView(s.id(), s.title(), s.url(), s.type(), s.enabled(),
                path == null ? null : path.getFileName().toString());
    }

    private SourceRecord find(String id) {
        return store.listSources().stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    private static PassageView passage(ScoredChunk h) {
        String t = h.chunk().text();
        return new PassageView(h.chunk().id(), h.source().title(), h.source().url(), h.chunk().section(), h.chunk().page(),
                t.length() > 400 ? t.substring(0, 400) + "…" : t, h.score());
    }

    private static MediaType mediaType(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        if (name.endsWith(".docx")) return DOCX;
        if (name.endsWith(".txt") || name.endsWith(".md")) return MediaType.TEXT_PLAIN;
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
