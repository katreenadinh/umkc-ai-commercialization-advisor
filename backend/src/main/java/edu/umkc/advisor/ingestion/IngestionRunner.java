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
 * Fetches sources.json entries and ingests any PDF/DOCX/TXT/MD files found in ingest.dir.
 * Prints extracted-text previews so empty or malformed documents are caught.
 */
@Component
@Profile("ingest")
public class IngestionRunner implements CommandLineRunner {
    private final IngestionPipeline pipeline;
    private final String dir;

    public IngestionRunner(IngestionPipeline pipeline, @Value("${ingest.dir:../data/imports}") String dir) {
        this.pipeline = pipeline; this.dir = dir;
    }

    @Override
    public void run(String... args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("sources.json")) {
            for (JsonNode s : mapper.readTree(in)) {
                try {
                    ExtractedDocument doc = new HtmlLoader().fetch(s.get("url").asText(), s.get("title").asText());
                    print(pipeline.ingest(s.get("id").asText(), doc, s.get("url").asText(), "HTML"), doc);
                } catch (Exception e) {
                    System.out.println("FAILED " + s.get("url").asText() + ": " + e.getClass().getSimpleName() + " (" + e.getMessage() + ")");
                }
            }
        }
        Path d = Path.of(dir);
        if (Files.isDirectory(d)) {
            try (Stream<Path> files = Files.list(d)) {
                for (Path f : (Iterable<Path>) files::iterator) ingestFile(f);
            }
        }
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
