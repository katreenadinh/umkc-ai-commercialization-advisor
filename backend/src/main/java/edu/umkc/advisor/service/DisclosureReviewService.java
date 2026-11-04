package edu.umkc.advisor.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umkc.advisor.ai.LlmClient;
import edu.umkc.advisor.ai.ResponseValidator;
import edu.umkc.advisor.ai.ReviewPayload;
import edu.umkc.advisor.exception.InvalidModelOutputException;
import edu.umkc.advisor.exception.LlmUnavailableException;
import edu.umkc.advisor.model.*;
import edu.umkc.advisor.safety.OutputGuard;
import edu.umkc.advisor.safety.ReferralDecision;
import edu.umkc.advisor.safety.ReferralService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Completeness-of-information review. Java decides everything it can without the model (empty answers, researcher marks);
 * the model only judges how complete a written answer is, and its output is validated and grounded before display.
 * Results are cached per field by answer hash, so editing one answer re-reviews only that answer.
 */
public final class DisclosureReviewService {

    public record FieldReview(String fieldId, String section, String label, FieldStatus status, String currentInformation,
                              String missingDetails, String followUpQuestion, String humanReviewNote, String origin,
                              boolean officialFormField, String researcherText, ResearcherMark mark, String answerHash) {}

    public record ReviewResult(List<FieldReview> fields, Progress progress, Map<String, Integer> counts,
                               boolean referralRequired, String referralNote, boolean automaticReviewComplete,
                               String reviewedAt, String disclaimer) {}

    public static final String DISCLAIMER =
            "This shows how complete your information is for disclosure preparation. It says nothing about patentability, "
          + "novelty, quality, or commercial potential, and you do not need to complete everything.";
    private static final int BATCH = 5;
    private static final Pattern EVALUATIVE = Pattern.compile(
            "\\b(lacks?\\s+novelty|not\\s+(?:novel|patentable|commercially\\s+viable|worth)|unlikely\\s+to\\s+be\\s+(?:patentable|novel)"
          + "|weak|poor|not\\s+(?:a\\s+)?(?:good|strong)|(?:is|are|seems?)\\s+(?:not\\s+)?(?:novel|patentable|innovative|valuable|promising)"
          + "|commercial(?:ly)?\\s+(?:value|potential)\\s+is)\\b", Pattern.CASE_INSENSITIVE);

    private final ChecklistService checklist;
    private final ReferralService referral;
    private final LlmClient llm;
    private final ResponseValidator validator;
    private final String systemPrompt;
    private final int maxTokens;
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final OutputGuard guard = new OutputGuard();

    public DisclosureReviewService(ChecklistService checklist, ReferralService referral, LlmClient llm,
                                   ResponseValidator validator, String systemPrompt, int maxTokens) {
        this.checklist = checklist; this.referral = referral; this.llm = llm; this.validator = validator;
        this.systemPrompt = systemPrompt; this.maxTokens = maxTokens;
    }

    public ReviewResult review(Draft d) {
        Map<String, FieldReview> cached = parseCache(d.reviewJson());
        Map<String, FieldReview> out = new HashMap<>();
        List<ChecklistField> needLlm = new ArrayList<>();
        for (ChecklistField f : checklist.applicable(d.workflow())) {
            FieldAnswer a = d.answer(f.id());
            String hash = hash(a);
            FieldReview decided = decideWithoutModel(f, a, hash);
            if (decided != null) out.put(f.id(), decided);
            else if (cached.containsKey(f.id()) && hash.equals(cached.get(f.id()).answerHash())) out.put(f.id(), cached.get(f.id()));
            else needLlm.add(f);
        }
        boolean complete = true;
        for (int i = 0; i < needLlm.size(); i += BATCH) {
            List<ChecklistField> batch = needLlm.subList(i, Math.min(needLlm.size(), i + BATCH));
            Map<String, ReviewPayload.Entry> entries = callModel(d, batch);
            for (ChecklistField f : batch) {
                FieldAnswer a = d.answer(f.id());
                ReviewPayload.Entry e = entries == null ? null : entries.get(f.id());
                if (e == null) { out.put(f.id(), unavailable(f, a)); complete = false; }
                else out.put(f.id(), reconcile(f, a, e, hash(a)));
            }
        }
        List<FieldReview> ordered = new ArrayList<>();
        for (ChecklistField f : checklist.applicable(d.workflow())) ordered.add(out.get(f.id()));

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (FieldStatus s : FieldStatus.values()) counts.put(s.name(), 0);
        int addressed = 0, na = 0, partial = 0;
        for (FieldReview r : ordered) {
            counts.merge(r.status().name(), 1, Integer::sum);
            switch (r.status()) {
                case NOT_APPLICABLE -> na++;
                case PARTIALLY_PROVIDED -> partial++;
                case PROVIDED, NOT_YET_KNOWN, NEEDS_HUMAN_REVIEW -> addressed++;
                default -> { }
            }
        }
        StringBuilder all = new StringBuilder();
        d.answers().values().forEach(a -> all.append(a.text()).append('\n'));
        ReferralDecision rule = referral.evaluate(all.toString());
        return new ReviewResult(ordered, Progress.of(addressed, ordered.size(), na, partial), counts, rule.referralRequired(),
                rule.referralNote(), complete, Instant.now().toString(), DISCLAIMER);
    }

    public String toJson(ReviewResult r) {
        try { return mapper.writeValueAsString(r); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** True when every field review was computed for the answer text currently saved (no edits since). */
    public boolean isFresh(Draft d, ReviewResult r) {
        if (r == null) return false;
        Map<String, FieldReview> byId = new HashMap<>();
        r.fields().forEach(f -> byId.put(f.fieldId(), f));
        for (ChecklistField f : checklist.applicable(d.workflow())) {
            FieldReview fr = byId.get(f.id());
            if (fr == null || !hash(d.answer(f.id())).equals(fr.answerHash())) return false;
        }
        return true;
    }

    public ReviewResult parse(String json) {
        if (json == null || json.isBlank()) return null;
        try { return mapper.readValue(json, ReviewResult.class); } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------------ decisions made in Java

    private FieldReview decideWithoutModel(ChecklistField f, FieldAnswer a, String hash) {
        if (a.mark() == ResearcherMark.NOT_YET_KNOWN)
            return build(f, a, hash, FieldStatus.NOT_YET_KNOWN,
                    a.hasText() ? excerpt(a.text()) : "The researcher indicated this is not yet known.", "", "");
        if (a.mark() == ResearcherMark.NOT_APPLICABLE) {
            if (f.notApplicableAllowed())
                return build(f, a, hash, FieldStatus.NOT_APPLICABLE, a.hasText() ? excerpt(a.text()) : "The researcher said this does not apply.", "", "");
            return build(f, a, hash, FieldStatus.NEEDS_HUMAN_REVIEW, a.hasText() ? excerpt(a.text()) : "The researcher said this does not apply.",
                    "Staff should confirm whether this item can be left out.", "");
        }
        if (!a.hasText()) {
            String why = a.mark() == ResearcherMark.SKIPPED ? "Skipped for now. You can come back to this anytime." : "Not addressed yet.";
            return build(f, a, hash, FieldStatus.MISSING, "", why, f.starterQuestion());
        }
        return null;
    }

    private FieldReview reconcile(ChecklistField f, FieldAnswer a, ReviewPayload.Entry e, String hash) {
        FieldStatus st = switch (e.status()) {
            case PROVIDED, PARTIALLY_PROVIDED, NEEDS_HUMAN_REVIEW -> e.status();
            default -> FieldStatus.PARTIALLY_PROVIDED;       // the model cannot claim missing / unknown / n.a. for text that exists
        };
        String current = e.currentInformation() == null ? "" : e.currentInformation().strip();
        if (current.isBlank() || TextOverlap.coverage(current, a.text()) < 0.6 || !GroundingCheck.unsupportedNumbers(current, a.text(), "").isEmpty()
                || unsafe(current)) current = excerpt(a.text());

        String missing = e.missingDetails() == null ? "" : e.missingDetails().strip();
        String follow = e.followUpQuestion() == null ? "" : e.followUpQuestion().strip();
        if (st == FieldStatus.PROVIDED) { missing = ""; follow = ""; }
        else {
            if (missing.isBlank() || unsafe(missing)) {
                missing = st == FieldStatus.NEEDS_HUMAN_REVIEW ? "This is something qualified university staff need to assess."
                        : "Some details may be unclear. See the follow-up question.";
            }
            if (st == FieldStatus.PARTIALLY_PROVIDED && (follow.isBlank() || !follow.contains("?") || follow.length() > 300 || unsafe(follow)))
                follow = f.starterQuestion();
            if (st == FieldStatus.NEEDS_HUMAN_REVIEW && (!follow.isBlank() && (unsafe(follow) || follow.length() > 300))) follow = "";
        }
        return build(f, a, hash, st, current, missing, follow);
    }

    private FieldReview unavailable(ChecklistField f, FieldAnswer a) {
        return build(f, a, "", FieldStatus.NEEDS_HUMAN_REVIEW, excerpt(a.text()),
                "The automatic check could not be completed for this item. Please read your answer and add anything that seems unclear.",
                f.starterQuestion());
    }

    private boolean unsafe(String text) {
        Set<String> allowed = new HashSet<>();
        ReferralService.Contact c = referral.contact();
        if (c.url() != null) allowed.add(c.url());
        if (c.email() != null) allowed.add(c.email());
        return EVALUATIVE.matcher(text).find() || guard.scan(text, allowed).violated();
    }

    private FieldReview build(ChecklistField f, FieldAnswer a, String hash, FieldStatus st, String cur, String missing, String follow) {
        return new FieldReview(f.id(), f.section(), f.label(), st, cur, missing, follow, f.humanReviewNote(), f.origin(),
                f.officialFormField(), a.text(), a.mark(), hash);
    }

    // ------------------------------------------------------------------ model call

    private Map<String, ReviewPayload.Entry> callModel(Draft d, List<ChecklistField> batch) {
        StringBuilder sb = new StringBuilder();
        for (ChecklistField f : batch) {
            sb.append("<field id=\"").append(f.id()).append("\" label=\"").append(f.label().replace('"', '\'')).append("\" expects=\"")
              .append(f.description().replace('"', '\'')).append("\">\n")
              .append(d.answer(f.id()).text().replace("<", "\u2039").replace(">", "\u203a")).append("\n</field>\n");
        }
        String prompt = sb.toString();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String p = attempt == 0 ? prompt : prompt + "\nYour previous reply did not match the JSON schema. Return only the JSON object.";
                ReviewPayload payload = validator.parseReview(llm.complete(systemPrompt, p, maxTokens));
                Map<String, ReviewPayload.Entry> m = new HashMap<>();
                Set<String> wanted = new HashSet<>();
                batch.forEach(f -> wanted.add(f.id()));
                for (ReviewPayload.Entry e : payload.fields()) if (wanted.contains(e.fieldId())) m.putIfAbsent(e.fieldId(), e);
                return m;
            } catch (InvalidModelOutputException ignored) {
                // retry once, then fall back
            } catch (LlmUnavailableException e) {
                return null;     // degrade gracefully: Java-decided fields are still returned
            }
        }
        return null;
    }

    private Map<String, FieldReview> parseCache(String json) {
        Map<String, FieldReview> m = new HashMap<>();
        ReviewResult r = parse(json);
        if (r != null && r.fields() != null) for (FieldReview f : r.fields()) if (f.answerHash() != null && !f.answerHash().isEmpty()) m.put(f.fieldId(), f);
        return m;
    }

    static String hash(FieldAnswer a) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((a.text() + "\u0000" + a.mark()).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String excerpt(String s) { String t = s.replaceAll("\\s+", " ").strip(); return t.length() <= 280 ? t : t.substring(0, 280) + "…"; }
}
