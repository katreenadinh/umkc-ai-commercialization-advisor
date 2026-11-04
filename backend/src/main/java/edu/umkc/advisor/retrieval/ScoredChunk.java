package edu.umkc.advisor.retrieval;

public record ScoredChunk(Chunk chunk, SourceRecord source, double score) {}
