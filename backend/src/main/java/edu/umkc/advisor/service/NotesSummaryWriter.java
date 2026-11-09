package edu.umkc.advisor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umkc.advisor.ai.LlmClient;
import edu.umkc.advisor.model.ChecklistField;
import edu.umkc.advisor.model.Draft;
import edu.umkc.advisor.model.FieldAnswer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes a short plain-language summary of each section from the researcher's saved answers. A summary is dropped if it
 * mentions a number, name, or judgement word that is not in the researcher's own text, so the notes never gain facts.
 * Any model failure returns no summaries and the notes fall back to the researcher's answers alone.
 */
public final class NotesSummaryWriter {
    private static final int MAX_SUMMARY_CHARS = 700;
    private static final int CACHE_SIZE = 200;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*");
    private static final Set<String> JUDGEMENTS = Set.of("novel", "novelty", "unique", "patent", "patentable", "patented",
            "breakthrough", "revolutionary", "groundbreaking", "guarantee", "guaranteed", "valuable", "superior", "best",
            "first", "innovative", "proven", "license", "licensed", "profitable", "market", "better", "cheaper", "faster", "easier",
            "reliable", "efficient", "effective", "improved", "advantage", "advantages", "outperforms");
    private static final Set<String> ALLOWED_NAMES = Set.of("I", "UMKC", "The", "This", "It", "They", "Their", "Researcher");

    private final LlmClient llm;
    private final String systemPrompt;
    private final int maxTokens;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Map<String, String>> cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Map<String, String>> e) { return size() > CACHE_SIZE; }
    });

    public NotesSummaryWriter(LlmClient llm, String systemPrompt, int maxTokens) {
        this.llm = llm; this.systemPrompt = systemPrompt; this.maxTokens = maxTokens;
    }

    /** @return section id to summary, only for sections that have written answers and passed the grounding check */
    public Map<String, String> write(Draft d, List<ChecklistService.Section> sections) {
        Map<String, String> sources = new LinkedHashMap<>();
        StringBuilder prompt = new StringBuilder("Summarize each section below.\n\n");
        for (ChecklistService.Section s : sections) {
            StringBuilder notes = new StringBuilder();
            for (ChecklistField f : s.fields()) {
                FieldAnswer a = d.answer(f.id());
                if (a.hasText()) notes.append("- ").append(f.label()).append(": ").append(a.text().replaceAll("\\s+", " ")).append('\n');
            }
            if (notes.isEmpty()) continue;
            sources.put(s.section().id(), notes.toString());
            prompt.append("<section id=\"").append(s.section().id()).append("\" title=\"").append(s.section().title()).append("\">\n")
                  .append(notes).append("</section>\n\n");
        }
        if (sources.isEmpty()) return Map.of();
        String key = hash(prompt.toString());
        Map<String, String> cached = cache.get(key);
        if (cached != null) return cached;

        Map<String, String> out = new LinkedHashMap<>();
        try {
            JsonNode root = mapper.readTree(jsonObject(llm.complete(systemPrompt, prompt.toString(), maxTokens)));
            for (JsonNode n : root.path("sections")) {
                String id = n.path("id").asText(""), summary = n.path("summary").asText("").strip();
                String source = sources.get(id);
                if (source == null || out.containsKey(id) || summary.isEmpty() || summary.length() > MAX_SUMMARY_CHARS) continue;
                if (grounded(summary, source)) out.put(id, summary.replaceAll("\\s+", " "));
            }
        } catch (Exception e) {
            return Map.of();
        }
        cache.put(key, Map.copyOf(out));
        return out;
    }

    /** True when every number, mid-sentence capitalized word, and judgement word in the summary also appears in the source. */
    static boolean grounded(String summary, String source) {
        String src = source.toLowerCase(Locale.ROOT);
        Matcher m = WORD.matcher(summary);
        while (m.find()) {
            String w = m.group();
            String lower = w.toLowerCase(Locale.ROOT);
            boolean sentenceStart = startsSentence(summary, m.start());
            boolean needsSource = w.chars().anyMatch(Character::isDigit)
                    || JUDGEMENTS.contains(lower)
                    || (!sentenceStart && Character.isUpperCase(w.charAt(0)) && !ALLOWED_NAMES.contains(w));
            if (needsSource && !src.contains(lower)) return false;
        }
        return true;
    }

    private static boolean startsSentence(String text, int at) {
        for (int i = at - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '"' || c == '(' || c == '“') continue;
            return c == '.' || c == '!' || c == '?' || c == ':';
        }
        return true;
    }

    private static String jsonObject(String reply) {
        int start = reply.indexOf('{'), end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("No JSON object in reply");
        return reply.substring(start, end + 1);
    }

    private static String hash(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
