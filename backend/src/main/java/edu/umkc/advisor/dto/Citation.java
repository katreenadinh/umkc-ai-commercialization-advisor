package edu.umkc.advisor.dto;

/**
 * Built only from stored source metadata and the retrieved passage. The model supplies a label such as "S1" and
 * nothing else; titles, URLs and sections are never taken from model output.
 */
public record Citation(String label, String chunkId, String title, String url, String section, Integer page, String excerpt) {}
