package edu.umkc.advisor.ingestion;

/** A run of text under one section heading and (optionally) one page. */
public record Segment(String section, Integer page, String text) {}
