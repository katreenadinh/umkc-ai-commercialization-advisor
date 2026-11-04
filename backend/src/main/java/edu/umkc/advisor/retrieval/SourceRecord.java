package edu.umkc.advisor.retrieval;

public record SourceRecord(String id, String title, String url, String type, String contentHash, boolean enabled) {}
