package edu.umkc.advisor.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class TextCleaner {
    private static final Set<String> BOILERPLATE = Set.of(
            "skip to content", "skip to navigation", "skip to search", "apply", "visit", "get info", "give",
            "main navigation", "search", "section navigation", "webmail", "libraries", "accreditation");

    private TextCleaner() {}

    /** Normalizes whitespace, drops navigation boilerplate and consecutive duplicate lines. Keeps paragraph breaks. */
    public static String clean(String raw) {
        if (raw == null) return "";
        String t = raw.replace('\u00a0', ' ').replace("\r", "");
        List<String> out = new ArrayList<>();
        String prev = null;
        for (String line : t.split("\n")) {
            String l = line.replaceAll("[ \\t]+", " ").trim();
            if (!l.isEmpty() && (l.length() < 3 || BOILERPLATE.contains(l.toLowerCase()))) continue;
            if (l.equals(prev) && !l.isEmpty()) continue;
            if (l.isEmpty() && (out.isEmpty() || out.get(out.size() - 1).isEmpty())) continue;
            out.add(l);
            prev = l;
        }
        return String.join("\n", out).trim();
    }
}
