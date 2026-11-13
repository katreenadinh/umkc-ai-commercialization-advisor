package edu.umkc.advisor.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocumentRetrievalServiceTest {

    private static ScoredChunk hit(String id, String text, double score) {
        SourceRecord src = new SourceRecord("src", "Synthetic source", "https://example.edu/x", "TEXT", "h", true);
        return new ScoredChunk(new Chunk(id, "src", 0, "sec", null, text), src, score);
    }

    @Test
    void keywordHitIsKeptEvenWhenDenseScoreIsBelowMinScore() {
        List<ScoredChunk> dense = List.of(
                hit("a", "Net proceeds: 33.3 percent inventor share.", 0.91),
                hit("b", "SBIR Phase I and UM-16C conflict of interest rules.", 0.10));
        List<ScoredChunk> sparse = List.of(hit("b", "SBIR Phase I and UM-16C conflict of interest rules.", 0.80));
        List<ScoredChunk> out = DocumentRetrievalService.fuse(dense, sparse, 2, 0.25);
        assertEquals(2, out.size());
        assertTrue(out.stream().anyMatch(c -> c.chunk().id().equals("b")));
    }

    @Test
    void denseOnlyStillDropsHitsBelowMinScore() {
        List<ScoredChunk> dense = List.of(
                hit("a", "Net proceeds: 33.3 percent inventor share.", 0.91),
                hit("b", "Unrelated passage about parking.", 0.10));
        List<ScoredChunk> out = DocumentRetrievalService.fuse(dense, List.of(), 2, 0.25);
        assertEquals(1, out.size());
        assertEquals("a", out.get(0).chunk().id());
    }

    @Test
    void inMemoryKeywordSearchFindsExactTerms() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        SourceRecord src = new SourceRecord("src", "UITS ethics", null, "TEXT", "h", true);
        Chunk chunk = new Chunk("c1", "src", 0, "COI", null, "SBIR Phase I awards and UM-16C conflict of interest.");
        store.replaceSource(src, List.of(chunk), List.of(new float[]{1f, 0f}));
        List<ScoredChunk> hits = store.searchKeyword("SBIR UM-16C", 4);
        assertEquals(1, hits.size());
        assertEquals("c1", hits.get(0).chunk().id());
        assertTrue(store.searchKeyword("quantum chromodynamics", 4).isEmpty());
    }
}
