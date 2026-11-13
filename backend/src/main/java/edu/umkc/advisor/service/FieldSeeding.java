package edu.umkc.advisor.service;

import edu.umkc.advisor.model.FieldAnswer;
import edu.umkc.advisor.model.ResearcherMark;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic splits of researcher wording into inventor / funding fields the coach now asks about.
 * Does not overwrite a heading that already has text.
 */
final class FieldSeeding {
    private static final Pattern DEPARTMENT = Pattern.compile(
            "(?i)\\b(?:department of|dept\\.? of|school of|college of)\\s+[\\w][\\w .'-]{2,50}"
          + "|\\bfrom the\\s+[\\w][\\w .'-]{2,40}\\s+(?:department|school|college|lab(?:oratory)?)");
    private static final Pattern CONTRIBUTION = Pattern.compile(
            "(?i)\\b([A-Z][a-z]+)\\s+(?:did|built|wrote|designed|ran|handled|developed)\\s+(?:the\\s+)?([^.;]{3,80})");
    private static final Pattern GRANT_NUMBER = Pattern.compile(
            "(?i)\\b(?:award|grant)\\s*(?:no\\.?|number|#)?\\s*([A-Z]{1,5}[-\\s]?\\d{2,}[A-Z0-9-]*)");
    private static final Pattern CONCEPT = Pattern.compile(
            "(?i)\\b(helped shape|core ideas?|came up with the (?:idea|concept)|developed the (?:underlying )?concept)\\b");

    private FieldSeeding() {}

    static List<String> apply(Map<String, FieldAnswer> answers, String said) {
        List<String> changed = new ArrayList<>();
        if (answers == null || said == null || said.isBlank()) return changed;
        String pool = said + " " + text(answers, "potential_inventors") + " " + text(answers, "inventor_contributions");
        seed(answers, changed, "inventor_departments", join(DEPARTMENT, pool));
        seed(answers, changed, "inventor_contributions", contributions(pool));
        if (CONCEPT.matcher(said).find()) seed(answers, changed, "concept_participants", clip(said, 400));
        seed(answers, changed, "grant_numbers", join(GRANT_NUMBER, said, 1));
        return changed;
    }

    private static void seed(Map<String, FieldAnswer> answers, List<String> changed, String id, String text) {
        if (text == null || text.isBlank()) return;
        FieldAnswer old = answers.getOrDefault(id, FieldAnswer.EMPTY);
        if (old.hasText()) return;
        answers.put(id, new FieldAnswer(text, ResearcherMark.NONE));
        if (!changed.contains(id)) changed.add(id);
    }

    private static String contributions(String pool) {
        Matcher m = CONTRIBUTION.matcher(pool);
        Set<String> parts = new LinkedHashSet<>();
        while (m.find()) {
            String who = m.group(1).strip();
            String what = m.group(2).replaceAll("\\s+", " ").strip();
            if (what.length() < 3) continue;
            parts.add(who + " " + what);
        }
        return parts.isEmpty() ? "" : String.join("; ", parts);
    }

    private static String join(Pattern p, String text) { return join(p, text, 0); }

    private static String join(Pattern p, String text, int group) {
        Matcher m = p.matcher(text);
        Set<String> parts = new LinkedHashSet<>();
        while (m.find()) {
            String g = (group > 0 && m.groupCount() >= group && m.group(group) != null) ? m.group(group) : m.group();
            String t = g.replaceAll("\\s+", " ").strip();
            if (t.length() >= 3) parts.add(t);
        }
        return parts.isEmpty() ? "" : String.join("; ", parts);
    }

    private static String text(Map<String, FieldAnswer> answers, String id) {
        FieldAnswer a = answers.get(id);
        return a == null || !a.hasText() ? "" : a.text();
    }

    private static String clip(String s, int max) {
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
