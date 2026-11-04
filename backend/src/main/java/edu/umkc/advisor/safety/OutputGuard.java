package edu.umkc.advisor.safety;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Last line of defense: blocks definitive determinations and invented URLs or emails in model output. */
public final class OutputGuard {

    public record Result(boolean violated, List<String> reasons, String text) {}

    public static final String SAFE_FALLBACK =
            "I can share general information about intellectual property and invention disclosure, but I can't "
          + "make determinations about your specific situation, such as patentability, ownership, inventorship, "
          + "or commercial value. Those need a qualified person at the university's commercialization office. "
          + "Would you like to ask a more general question, or would you like to know how to contact the office?";

    private static final int CI = Pattern.CASE_INSENSITIVE;
    private static final String SUBJ = "(?:your|the|this)\\s+(?:invention|software|device|method|research|discovery|idea|technology|work)";

    private record Rule(String name, Pattern pattern, boolean skipIfNegated) {}
    private static final List<Rule> RULES = List.of(
        new Rule("DEFINITIVE_PATENTABILITY_OR_NOVELTY", Pattern.compile(
            "\\b" + SUBJ + "\\s+(?:is|are|would\\s+be|will\\s+be)\\s+(?:definitely\\s+|clearly\\s+|certainly\\s+)?(?:not\\s+)?"
            + "(?:patentable|novel|non-?obvious|commercially\\s+viable|worth\\s+(?:patenting|commercializing))", CI), false),
        new Rule("DEFINITIVE_INVENTORSHIP", Pattern.compile(
            "\\byou\\s+(?:are|'re)\\s+(?:the\\s+|a\\s+)?(?:sole\\s+|co-?\\s?|joint\\s+)?inventor"
            + "|\\byou\\s+(?:do\\s+not|don't)\\s+qualify\\s+as\\s+(?:an?\\s+)?inventor", CI), false),
        new Rule("DEFINITIVE_OWNERSHIP", Pattern.compile(
            "\\byou\\s+(?:own|will\\s+own|do\\s+not\\s+own|don't\\s+own|retain\\s+ownership)\\b"
            + "|\\bthe\\s+university\\s+(?:owns|will\\s+own|does\\s+not\\s+own|doesn't\\s+own)\\s+(?:your|this\\s+invention)", CI), false),
        new Rule("GUARANTEE", Pattern.compile(
            "\\bguarantee[sd]?\\b[^.?!]{0,30}\\b(?:protection|patent|rights)", CI), true),
        new Rule("DEFINITIVE_PUBLIC_DISCLOSURE_ADVICE", Pattern.compile(
            "\\b(?:it\\s+is|it's|it\\s+should\\s+be|you\\s+can)\\s+(?:safe|fine|okay|ok)\\s+to\\s+(?:publish|present|share|disclose|demonstrate)", CI), false),
        new Rule("DISCOURAGES_CONTACT", Pattern.compile(
            "\\bno\\s+need\\s+to\\s+(?:contact|talk\\s+to|notify)\\s+(?:the\\s+)?(?:office|otc|ott|staff|university)"
            + "|\\bwait\\s+until\\s+(?:your\\s+)?(?:work|research|invention)\\s+is\\s+(?:more\\s+)?(?:mature|developed|complete|finished)", CI), false),
        new Rule("DEFINITIVE_UNIVERSITY_ACTION", Pattern.compile(
            "\\bthe\\s+university\\s+will\\s+(?:definitely\\s+|certainly\\s+)?(?:file|pursue|license|commercialize)\\b", CI), true)
    );

    private static final Pattern URL = Pattern.compile("https?://[^\\s)>\\]\"']+", CI);
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern NEGATION = Pattern.compile("(?:\\bnot\\b|n't\\b|\\bnever\\b|\\bno\\b)[^.?!]{0,60}$", CI);

    /**
     * @param allowedUrlsAndEmails URLs and emails that are verified (retrieved source URLs and configured contact)
     */
    public Result scan(String answer, Set<String> allowedUrlsAndEmails) {
        if (answer == null) return new Result(false, List.of(), "");
        List<String> reasons = new ArrayList<>();
        for (Rule r : RULES) {
            Matcher m = r.pattern.matcher(answer);
            while (m.find()) {
                if (r.skipIfNegated) {
                    String before = answer.substring(Math.max(0, m.start() - 80), m.start());
                    if (NEGATION.matcher(before).find()) continue;
                }
                reasons.add(r.name);
                break;
            }
        }
        Set<String> allowed = normalizeAll(allowedUrlsAndEmails);
        Matcher u = URL.matcher(answer);
        while (u.find()) {
            if (!allowed.contains(normalize(u.group()))) { reasons.add("UNVERIFIED_URL"); break; }
        }
        Matcher e = EMAIL.matcher(answer);
        while (e.find()) {
            if (!allowed.contains(normalize(e.group()))) { reasons.add("UNVERIFIED_EMAIL"); break; }
        }
        return reasons.isEmpty() ? new Result(false, List.of(), answer) : new Result(true, reasons, SAFE_FALLBACK);
    }

    private static Set<String> normalizeAll(Set<String> in) {
        java.util.HashSet<String> out = new java.util.HashSet<>();
        if (in != null) for (String s : in) if (s != null) out.add(normalize(s));
        return out;
    }

    private static String normalize(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        while (t.endsWith(".") || t.endsWith(",") || t.endsWith(";") || t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }
}
