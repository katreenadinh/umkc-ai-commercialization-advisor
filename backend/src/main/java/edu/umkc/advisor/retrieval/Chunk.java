package edu.umkc.advisor.retrieval;

public record Chunk(String id, String sourceId, int index, String section, Integer page, String text) {}
