package edu.umkc.advisor.ingestion;

import edu.umkc.advisor.retrieval.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkerTest {
    private static String sentences(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("This is sentence number ").append(i).append(" about invention disclosure. ");
        return sb.toString();
    }

    @Test
    void shortSegmentIsOneChunkWithMetadata() {
        List<Chunk> c = Chunker.defaults().chunk("s1", List.of(new Segment("Intro", 3, "A short paragraph that is long enough to keep as a chunk of its own.")));
        assertEquals(1, c.size());
        assertEquals("Intro", c.get(0).section());
        assertEquals(3, c.get(0).page());
    }

    @Test
    void longTextSplitsWithOverlapAndRespectsTarget() {
        Chunker ch = new Chunker(400, 80, 40);
        List<Chunk> c = ch.chunk("s1", List.of(new Segment("Long", null, sentences(40))));
        assertTrue(c.size() > 3);
        for (Chunk x : c) assertTrue(x.text().length() <= 400 + 80 + 5, "chunk too long: " + x.text().length());
        // overlap: end of chunk i appears at start of chunk i+1
        String tail = c.get(0).text().substring(c.get(0).text().length() - 40);
        assertTrue(c.get(1).text().contains(tail.trim().split(" ")[tail.trim().split(" ").length - 1]));
        for (int i = 0; i < c.size(); i++) assertEquals(i, c.get(i).index());
    }

    @Test
    void chunksNeverCrossSections() {
        List<Chunk> c = Chunker.defaults().chunk("s", List.of(
                new Segment("A", null, "Alpha text that is reasonably long to be a chunk by itself in the test."),
                new Segment("B", null, "Beta text that is reasonably long to be a chunk by itself in the test.")));
        assertEquals(2, c.size());
        assertFalse(c.get(0).text().contains("Beta"));
    }

    @Test
    void runOnTextWithoutPunctuationIsHardSplit() {
        List<Chunk> c = new Chunker(300, 50, 20).chunk("s", List.of(new Segment("X", null, "word ".repeat(400))));
        assertTrue(c.size() >= 5);
        c.forEach(x -> assertTrue(x.text().length() <= 360));
    }

    @Test
    void rejectsBadConfig() {
        assertThrows(IllegalArgumentException.class, () -> new Chunker(100, 100, 10));
    }

    @Test
    void cleanerRemovesBoilerplateAndDuplicates() {
        String out = TextCleaner.clean("Skip to Content\nApply\nReal content here\nReal content here\n\n\n\nMore content here");
        assertEquals("Real content here\n\nMore content here", out);
    }
}
