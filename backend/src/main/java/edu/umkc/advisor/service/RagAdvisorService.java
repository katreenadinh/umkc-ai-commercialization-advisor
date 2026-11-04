package edu.umkc.advisor.service;

import edu.umkc.advisor.ai.AnswerPayload;
import edu.umkc.advisor.ai.LlmClient;
import edu.umkc.advisor.ai.ResponseValidator;
import edu.umkc.advisor.dto.Citation;
import edu.umkc.advisor.exception.InvalidModelOutputException;
import edu.umkc.advisor.retrieval.DocumentRetrievalService;
import edu.umkc.advisor.retrieval.ScoredChunk;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Retrieval-augmented answers with Java-built citations and fallbacks when sources do not support an answer. */
public final class RagAdvisorService implements AnswerGenerator {

    public static final String NO_SOURCES_ANSWER =
            "I couldn't find this in the approved university sources I have, so I can't answer it confidently and "
          + "I don't want to guess. The commercialization office can help with this directly, and early, informal "
          + "questions are welcome.";

    private static final Pattern MARKER = Pattern.compile("\\[(S\\d+)]");
    private static final int EXCERPT_CHARS = 300;

    private final DocumentRetrievalService retrieval;
    private final LlmClient llm;
    private final ResponseValidator validator;
    private final String systemPrompt;
    private final int maxTokens;

    public RagAdvisorService(DocumentRetrievalService retrieval, LlmClient llm, ResponseValidator validator,
                             String systemPrompt, int maxTokens) {
        this.retrieval = retrieval; this.llm = llm; this.validator = validator;
        this.systemPrompt = systemPrompt; this.maxTokens = maxTokens;
    }

    @Override
    public GeneratedAnswer generate(String question) {
        List<ScoredChunk> hits = retrieval.retrieve(question);
        if (hits.isEmpty()) return insufficient();               // no LLM call: nothing to ground on

        Map<String, ScoredChunk> byLabel = new LinkedHashMap<>();
        StringBuilder docs = new StringBuilder("<retrieved_documents>\n");
        for (int i = 0; i < hits.size(); i++) {
            String label = "S" + (i + 1);
            ScoredChunk h = hits.get(i);
            byLabel.put(label, h);
            docs.append("<document label=\"").append(label).append("\" title=\"").append(attr(h.source().title()))
                .append("\" section=\"").append(attr(h.chunk().section())).append("\">\n")
                .append(sanitize(h.chunk().text())).append("\n</document>\n");
        }
        docs.append("</retrieved_documents>\n\n<user_question>\n").append(sanitize(question)).append("\n</user_question>");

        AnswerPayload payload;
        try {
            payload = validator.parseAnswer(llm.complete(systemPrompt, docs.toString(), maxTokens));
        } catch (InvalidModelOutputException first) {
            try {
                payload = validator.parseAnswer(llm.complete(systemPrompt,
                        docs + "\n\nYour previous reply did not match the JSON schema. Return only the JSON object.", maxTokens));
            } catch (InvalidModelOutputException second) {
                return insufficient();
            }
        }
        return buildAnswer(question, payload, byLabel);
    }

    private GeneratedAnswer buildAnswer(String question, AnswerPayload p, Map<String, ScoredChunk> byLabel) {
        // Valid labels = those actually retrieved. Anything else is dropped, never displayed.
        Set<String> cited = new LinkedHashSet<>();
        Matcher m = MARKER.matcher(p.answer());
        while (m.find()) if (byLabel.containsKey(m.group(1))) cited.add(m.group(1));
        for (String u : p.usedSources()) if (byLabel.containsKey(u)) cited.add(u);

        String text = stripInvalidMarkers(p.answer(), byLabel.keySet());
        if (cited.isEmpty()) return insufficient();              // claims with no verifiable support

        StringBuilder evidence = new StringBuilder();
        for (String label : cited) evidence.append(byLabel.get(label).chunk().text()).append(' ');
        if (!GroundingCheck.unsupportedNumbers(text, evidence.toString(), question).isEmpty()) return insufficient();

        List<Citation> citations = new ArrayList<>();
        for (String label : cited) {
            ScoredChunk h = byLabel.get(label);
            String t = h.chunk().text();
            citations.add(new Citation(label, h.chunk().id(), h.source().title(), h.source().url(), h.chunk().section(),
                    h.chunk().page(), t.length() > EXCERPT_CHARS ? t.substring(0, EXCERPT_CHARS) + "…" : t));
        }
        return new GeneratedAnswer(text, citations, !p.sufficientEvidence());
    }

    private static GeneratedAnswer insufficient() { return new GeneratedAnswer(NO_SOURCES_ANSWER, List.of(), true); }

    private static String stripInvalidMarkers(String text, Set<String> valid) {
        Matcher m = MARKER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, valid.contains(m.group(1)) ? Matcher.quoteReplacement(m.group()) : "");
        m.appendTail(sb);
        return sb.toString().replaceAll("[ \\t]{2,}", " ").trim();
    }

    /** Prevents document text from closing our delimiter tags or impersonating them. */
    static String sanitize(String s) { return s == null ? "" : s.replace("<", "\u2039").replace(">", "\u203a"); }

    private static String attr(String s) { return s == null ? "" : sanitize(s).replace("\"", "'"); }
}
