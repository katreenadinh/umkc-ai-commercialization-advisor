package edu.umkc.advisor.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cheap anti-fabrication check: numbers in an answer must appear in the retrieved text or the question. */
public final class GroundingCheck {
    private static final Pattern MARKER = Pattern.compile("\\[S\\d+]");
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    private GroundingCheck() {}

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
