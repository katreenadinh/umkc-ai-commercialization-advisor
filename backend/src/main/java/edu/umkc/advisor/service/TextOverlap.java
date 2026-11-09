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

    /** Fraction of the candidate's tokens found in the reference (allowing 1-character typos). 1.0 if the candidate has no tokens. */
    public static double coverage(String candidate, String reference) {
        Set<String> c = tokens(candidate);
        if (c.isEmpty()) return 1.0;
        Set<String> r = tokens(reference);
        int hit = 0;
        for (String t : c) {
            if (tokenMatches(t, r)) hit++;
        }
        return (double) hit / c.size();
    }

    private static boolean tokenMatches(String token, Set<String> reference) {
        if (reference.contains(token)) return true;
        for (String ref : reference) {
            if (isFuzzyMatch(token, ref)) return true;
        }
        return false;
    }

    private static boolean isFuzzyMatch(String a, String b) {
        if (a.equals(b)) return true;
        if (Math.abs(a.length() - b.length()) > 1) return false;
        if (a.length() >= 4 && b.length() >= 4) {
            int diff = 0;
            int i = 0, j = 0;
            while (i < a.length() && j < b.length()) {
                if (a.charAt(i) != b.charAt(j)) {
                    diff++;
                    if (diff > 1) return false;
                    if (a.length() > b.length()) i++;
                    else if (b.length() > a.length()) j++;
                    else { i++; j++; }
                } else {
                    i++; j++;
                }
            }
            return true;
        }
        return false;
    }
}
