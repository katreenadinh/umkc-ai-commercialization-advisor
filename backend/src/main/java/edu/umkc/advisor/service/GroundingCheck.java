package edu.umkc.advisor.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cheap anti-fabrication check: numbers and claim vocabulary must appear in the cited evidence. */
public final class GroundingCheck {
    private static final Pattern MARKER = Pattern.compile("\\[S\\d+]");
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");
    /** Fraction of answer tokens that must occur in the cited chunks when the model claims the evidence is sufficient. */
    static final double MIN_CITED_COVERAGE = 0.35;

    private GroundingCheck() {}

    /**
     * True when numbers in the answer appear in the <em>cited</em> chunks (not the whole retrieval pool)
     * and, if the model claimed sufficient evidence, the answer's wording overlaps those chunks.
     * Hedged answers ({@code sufficientEvidence=false}) skip the overlap check so the model can admit limits.
     */
    public static boolean citedSupport(String answer, String citedEvidence, String question, boolean sufficientEvidence) {
        if (!unsupportedNumbers(answer, citedEvidence, question).isEmpty()) return false;
        if (!sufficientEvidence) return true;
        String stripped = MARKER.matcher(answer == null ? "" : answer).replaceAll(" ");
        return TextOverlap.coverage(stripped, (citedEvidence == null ? "" : citedEvidence) + " " + (question == null ? "" : question))
                >= MIN_CITED_COVERAGE;
    }

    /** Returns numeric tokens (two or more digits, or decimals) that are not found in the evidence. */
    public static List<String> unsupportedNumbers(String answer, String evidenceText, String question) {
        String haystack = (evidenceText + " " + question).replace(",", "");
        List<String> bad = new ArrayList<>();
        Matcher m = NUMBER.matcher(MARKER.matcher(answer).replaceAll(" "));
        while (m.find()) {
            String tok = m.group().replace(",", "");
            boolean significant = tok.length() >= 2;
            if (significant && !haystack.contains(tok)) bad.add(tok);
        }
        return bad;
    }
}
