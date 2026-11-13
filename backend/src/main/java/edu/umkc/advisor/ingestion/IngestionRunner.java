package edu.umkc.advisor.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Separate from the request pipeline. Run with:
 *   mvn spring-boot:run -Dspring-boot.run.profiles=ingest
 * Optional notebook handoff: add -Dspring-boot.run.arguments=--ingest.jsonl=../data/chunks.jsonl
 * Fetches sources.json entries, then local files in ingest.dir and ingest.extra-dir
 * (eval/extracted_texts: UITS ethics/funding mirrors not in the five official URLs).
 * Prints extracted-text previews so empty or malformed documents are caught.
 */
@Component
@Profile("ingest")
public class IngestionRunner implements CommandLineRunner {
    private final IngestionPipeline pipeline;
    private final String dir;
    private final String extraDir;
    private final String jsonl;

    public IngestionRunner(IngestionPipeline pipeline, @Value("${ingest.dir:../data/imports}") String dir,
                           @Value("${ingest.extra-dir:../eval/extracted_texts}") String extraDir,
                           @Value("${ingest.jsonl:}") String jsonl) {
        this.pipeline = pipeline; this.dir = dir; this.extraDir = extraDir; this.jsonl = jsonl;
    }

    @Override
    public void run(String... args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("sources.json")) {
            for (JsonNode s : mapper.readTree(in)) {
                try {
                    String type = s.path("type").asText("HTML");
                    ExtractedDocument doc;
                    if ("DOCX_URL".equals(type)) {
                        var res = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build()
                                .send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(s.get("url").asText())).build(),
                                        java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                        doc = new DocxLoader().load(new java.io.ByteArrayInputStream(res.body()), s.get("title").asText());
                        type = "DOCX";
                    } else {
                        doc = new HtmlLoader().fetch(s.get("url").asText(), s.get("title").asText());
                    }
                    print(pipeline.ingest(s.get("id").asText(), doc, s.get("url").asText(), type), doc);
                } catch (Exception e) {
                    System.out.println("FAILED " + s.get("url").asText() + ": " + e.getClass().getSimpleName() + " (" + e.getMessage() + ")");
                }
            }
        }
        if (jsonl != null && !jsonl.isBlank()) {
            try (var reader = java.nio.file.Files.newBufferedReader(Path.of(jsonl))) {
                for (var r : new JsonlImporter(pipeline).importJsonl(reader)) System.out.printf("JSONL %s: %d chunks%n", r.title(), r.chunks());
            }
        }
        ingestDirectory(Path.of(dir), false);
        if (extraDir != null && !extraDir.isBlank() && !Path.of(extraDir).normalize().equals(Path.of(dir).normalize())) {
            ingestDirectory(Path.of(extraDir), true);
        }
    }

    private void ingestDirectory(Path d, boolean skipOfficialMirrors) throws Exception {
        if (!Files.isDirectory(d)) return;
        try (Stream<Path> files = Files.list(d)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (skipOfficialMirrors && officialMirror(f.getFileName().toString())) continue;
                ingestFile(f);
            }
        }
    }

    /** HTML/DOCX copies of the same CRR 100.020 / UM-16C sources already fetched from sources.json. */
    static boolean officialMirror(String fileName) {
        String n = fileName.toLowerCase().replace('_', ' ');
        return n.contains("100.020") || n.contains("um-16c") || n.contains("um 16c");
    }

    private void ingestFile(Path f) {
        String name = f.getFileName().toString(), lower = name.toLowerCase();
        String title = name.replaceAll("\\.[^.]+$", "");
        try (InputStream in = Files.newInputStream(f)) {
            ExtractedDocument doc; String type;
            if (lower.endsWith(".pdf")) { doc = new PdfLoader().load(in, title); type = "PDF"; }
            else if (lower.endsWith(".docx")) { doc = new DocxLoader().load(in, title); type = "DOCX"; }
            else if (lower.endsWith(".txt") || lower.endsWith(".md")) {
                doc = new ExtractedDocument(title, java.util.List.of(new Segment(title, null, new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)))); type = "TEXT";
            } else return;
            print(pipeline.ingest(java.util.UUID.nameUUIDFromBytes(("file:" + name).getBytes()).toString(), doc, null, type), doc);
        } catch (Exception e) {
            System.out.println("FAILED " + name + ": " + e.getClass().getSimpleName());
        }
    }

    private static void print(IngestionPipeline.Report r, ExtractedDocument doc) {
        System.out.printf("%s: %d chars, %d chunks%n", r.title(), r.chars(), r.chunks());
        r.warnings().forEach(w -> System.out.println("  WARNING: " + w));
        if (!doc.segments().isEmpty()) {
            String t = doc.segments().get(0).text();
            System.out.println("  preview: " + t.substring(0, Math.min(200, t.length())).replace('\n', ' '));
        }
    }
}
