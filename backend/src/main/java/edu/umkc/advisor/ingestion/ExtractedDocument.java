package edu.umkc.advisor.ingestion;

import java.util.List;

public record ExtractedDocument(String title, List<Segment> segments) {
    public int totalChars() { return segments.stream().mapToInt(s -> s.text().length()).sum(); }
}
