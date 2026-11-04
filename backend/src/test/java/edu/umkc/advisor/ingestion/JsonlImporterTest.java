package edu.umkc.advisor.ingestion;

import edu.umkc.advisor.retrieval.HashingEmbedder;
import edu.umkc.advisor.retrieval.InMemoryVectorStore;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

class JsonlImporterTest {
    @Test
    void importsGroupsBySourceAndReplacesOnReimport() throws Exception {
        InMemoryVectorStore store = new InMemoryVectorStore();
        JsonlImporter imp = new JsonlImporter(new IngestionPipeline(Chunker.defaults(), new HashingEmbedder(), store));
        String jsonl = """
            {"source_id":"doc-a","title":"Doc A","url":"https://example.edu/a","source_type":"PDF","section":"S1","page":2,"chunk_index":0,"text":"First synthetic chunk about invention disclosure forms and early contact."}
            {"source_id":"doc-a","title":"Doc A","url":"https://example.edu/a","source_type":"PDF","section":"S2","page":3,"chunk_index":1,"text":"Second synthetic chunk about publication timing and public disclosure."}
            {"source_id":"doc-b","title":"Doc B","source_type":"DOCX","text":"Another synthetic chunk about software and copyright disclosures."}
            """;
        var reports = imp.importJsonl(new StringReader(jsonl));
        assertEquals(2, reports.size());
        assertEquals(2, store.listSources().size());
        imp.importJsonl(new StringReader(jsonl));
        assertEquals(2, store.listSources().size());   // replaced, not duplicated
        var hit = store.search(new HashingEmbedder().embed("publication timing public disclosure"), 1).get(0);
        assertEquals(3, hit.chunk().page());
        assertEquals("Doc A", hit.source().title());
    }

    @Test
    void reportsMissingFieldsWithLineNumber() {
        JsonlImporter imp = new JsonlImporter(new IngestionPipeline(Chunker.defaults(), new HashingEmbedder(), new InMemoryVectorStore()));
        var e = assertThrows(IllegalArgumentException.class, () -> imp.importJsonl(new StringReader("{\"title\":\"x\",\"text\":\"y\"}")));
        assertTrue(e.getMessage().contains("Line 1"));
    }

    @Test
    void emptyDocumentIsNotStoredAndWarns() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        var r = new IngestionPipeline(Chunker.defaults(), new HashingEmbedder(), store)
                .ingest("id1", new ExtractedDocument("Empty", java.util.List.of()), null, "PDF");
        assertEquals(0, r.chunks());
        assertFalse(r.warnings().isEmpty());
        assertTrue(store.listSources().isEmpty());
    }
}
