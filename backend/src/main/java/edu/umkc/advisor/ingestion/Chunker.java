package edu.umkc.advisor.ingestion;

import edu.umkc.advisor.retrieval.Chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Paragraph/sentence-aware chunker. Defaults: 1200 characters (about 250-300 tokens) with 200 characters of overlap.
 * Chunks never cross segment (section/page) boundaries, so section and page metadata stay exact.
 */
public final class Chunker {
    private final int target, overlap, minChars;

    public Chunker(int targetChars, int overlapChars, int minChars) {
        if (overlapChars >= targetChars) throw new IllegalArgumentException("overlap must be < target");
        this.target = targetChars; this.overlap = overlapChars; this.minChars = minChars;
    }

    public static Chunker defaults() { return new Chunker(1200, 200, 60); }

    public List<Chunk> chunk(String sourceId, List<Segment> segments) {
        List<Chunk> out = new ArrayList<>();
        int index = 0;
        for (Segment seg : segments) {
            for (String piece : split(TextCleaner.clean(seg.text()))) {
                out.add(new Chunk(UUID.randomUUID().toString(), sourceId, index++, seg.section(), seg.page(), piece));
            }
        }
        return out;
    }

    List<String> split(String text) {
        List<String> units = new ArrayList<>();
        for (String para : text.split("\n{2,}|\n")) {
            String p = para.trim();
            if (p.isEmpty()) continue;
            if (p.length() <= target) { units.add(p); continue; }
            for (String s : p.split("(?<=[.!?])\\s+")) {
                String sent = s.trim();
                while (sent.length() > target) {            // pathological run-on text: hard split
                    units.add(sent.substring(0, target));
                    sent = sent.substring(target - overlap).trim();
                }
                if (!sent.isEmpty()) units.add(sent);
            }
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String u : units) {
            if (cur.length() > 0 && cur.length() + 1 + u.length() > target) {
                chunks.add(cur.toString());
                String tail = tail(cur.toString());
                cur = new StringBuilder(tail);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(u);
        }
        if (cur.length() > 0) chunks.add(cur.toString());
        // Merge a tiny trailing fragment into its predecessor rather than emitting a near-empty chunk.
        if (chunks.size() > 1 && chunks.get(chunks.size() - 1).length() < minChars) {
            String last = chunks.remove(chunks.size() - 1);
            chunks.set(chunks.size() - 1, chunks.get(chunks.size() - 1) + " " + last);
        }
        chunks.removeIf(c -> c.isBlank());
        return chunks;
    }

    /** Last ~overlap characters, starting at a word boundary. */
    private String tail(String s) {
        if (s.length() <= overlap) return s;
        String t = s.substring(s.length() - overlap);
        int sp = t.indexOf(' ');
        return sp >= 0 ? t.substring(sp + 1) : t;
    }
}
