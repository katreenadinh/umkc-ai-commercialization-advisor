package edu.umkc.advisor.service;

import java.util.HashSet;
import java.util.Set;

/** Cheap grounding test: how much of a candidate's vocabulary appears in the reference text. */
public final class TextOverlap {
    private TextOverlap() {}

    static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        for (String w : s.toLowerCase().split("[^a-z0-9]+")) {
            if (w.length() < 3) continue;
            out.add(w.endsWith("s") && w.length() > 3 ? w.substring(0, w.length() - 1) : w);
        }
        return out;
    }

    /** Fraction of the candidate's tokens found in the reference. 1.0 if the candidate has no tokens. */
    public static double coverage(String candidate, String reference) {
        Set<String> c = tokens(candidate);
        if (c.isEmpty()) return 1.0;
        Set<String> r = tokens(reference);
        int hit = 0;
        for (String t : c) if (r.contains(t)) hit++;
        return (double) hit / c.size();
    }
}
