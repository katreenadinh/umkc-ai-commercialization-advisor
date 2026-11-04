package edu.umkc.advisor.retrieval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SourceFileLocatorTest {
    @TempDir Path dir;

    @Test
    void findsPdfByTitleStemAndIgnoresHtmlSources() throws Exception {
        Path pdf = dir.resolve("100.020 Invention and Patent Regulations _ UM System.pdf");
        Files.writeString(pdf, "%PDF");
        SourceFileLocator locator = new SourceFileLocator(dir.toString());
        SourceRecord pdfSource = new SourceRecord("id", "100.020 Invention and Patent Regulations _ UM System",
                null, "PDF", "hash", true);
        SourceRecord html = new SourceRecord("html", "100.020 Invention and Patent Regulations _ UM System",
                "https://www.umsystem.edu/example", "HTML", "hash", true);
        assertEquals(pdf, locator.fileFor(pdfSource).orElseThrow());
        assertTrue(locator.fileFor(html).isEmpty());
    }

    @Test
    void findsFileFromIngestUuid() throws Exception {
        Path docx = dir.resolve("guide.docx");
        Files.writeString(docx, "docx");
        String id = UUID.nameUUIDFromBytes("file:guide.docx".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        SourceFileLocator locator = new SourceFileLocator(dir.toString());
        SourceRecord source = new SourceRecord(id, "Some other title", null, "DOCX", "hash", true);
        assertEquals(docx, locator.fileFor(source).orElseThrow());
    }
}
