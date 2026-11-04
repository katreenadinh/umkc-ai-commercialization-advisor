package edu.umkc.advisor.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umkc.advisor.exception.InvalidModelOutputException;
import edu.umkc.advisor.model.NextAction;
import edu.umkc.advisor.model.RelevanceCategory;

/** Parses and validates structured model output. Category is authoritative; inconsistent actions are coerced. */
public final class ResponseValidator {

    public static final String GENERIC_CLARIFICATION =
            "Could you tell me a bit more about what you'd like to know? For example, is your question about "
          + "intellectual property, an invention disclosure, or commercializing your research?";

    private final ObjectMapper mapper = new ObjectMapper();

    /** Extracts the JSON object from raw model text, tolerating code fences and surrounding prose. */
    public JsonNode parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) throw new InvalidModelOutputException("empty model output");
        int start = raw.indexOf('{'), end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) throw new InvalidModelOutputException("no JSON object in model output");
        try {
            JsonNode n = mapper.readTree(raw.substring(start, end + 1));
            if (!n.isObject()) throw new InvalidModelOutputException("model output is not a JSON object");
            return n;
        } catch (java.io.IOException e) {
            throw new InvalidModelOutputException("model output is not valid JSON", e);
        }
    }

    public RelevanceResult parseRelevance(String raw) {
        JsonNode n = parseJsonObject(raw);
        RelevanceCategory category = enumValue(n, "category", RelevanceCategory.class);
        NextAction action = enumValue(n, "nextAction", NextAction.class);
        String reason = n.path("reason").asText("").trim();
        if (reason.isEmpty()) throw new InvalidModelOutputException("missing reason");
        if (reason.length() > 300) reason = reason.substring(0, 300);
        if (!n.has("humanReferralNeeded") || !n.get("humanReferralNeeded").isBoolean()) {
            throw new InvalidModelOutputException("humanReferralNeeded must be a boolean");
        }
        boolean referral = n.get("humanReferralNeeded").asBoolean();
        double confidence = n.has("confidence") && n.get("confidence").isNumber() ? n.get("confidence").asDouble() : 0.5;
        if (confidence < 0 || confidence > 1) throw new InvalidModelOutputException("confidence out of range");
        String question = n.path("clarifyingQuestion").isNull() ? null : n.path("clarifyingQuestion").asText(null);
        if (question != null && question.isBlank()) question = null;

        switch (category) {
            case OUT_OF_SCOPE -> { action = NextAction.DECLINE_OUT_OF_SCOPE; question = null; }
            case UNCERTAIN -> {
                action = NextAction.ASK_CLARIFYING_QUESTION;
                if (question == null) question = GENERIC_CLARIFICATION;
            }
            case EXPERT_REVIEW, TIME_SENSITIVE -> referral = true;
            default -> { if (action == NextAction.DECLINE_OUT_OF_SCOPE) action = NextAction.ANSWER_WITH_RAG; }
        }
        if (action == NextAction.ASK_CLARIFYING_QUESTION && question == null) question = GENERIC_CLARIFICATION;
        return new RelevanceResult(category, action, reason, referral, question, confidence);
    }

    public AnswerPayload parseAnswer(String raw) {
        JsonNode n = parseJsonObject(raw);
        String answer = n.path("answer").asText("").trim();
        if (answer.isEmpty()) throw new InvalidModelOutputException("missing answer");
        if (answer.length() > 4000) throw new InvalidModelOutputException("answer too long");
        for (String f : new String[]{"sufficientEvidence", "needsStaffFollowUp"}) {
            if (!n.has(f) || !n.get(f).isBoolean()) throw new InvalidModelOutputException(f + " must be a boolean");
        }
        if (!n.has("usedSources") || !n.get("usedSources").isArray()) throw new InvalidModelOutputException("usedSources must be an array");
        java.util.List<String> used = new java.util.ArrayList<>();
        for (JsonNode u : n.get("usedSources")) used.add(u.asText("").trim());
        return new AnswerPayload(answer, used, n.get("sufficientEvidence").asBoolean(), n.get("needsStaffFollowUp").asBoolean());
    }

    public InterviewPayload parseInterview(String raw) {
        JsonNode n = parseJsonObject(raw);
        String msg = n.path("assistantMessage").asText("").trim();
        if (msg.isEmpty()) throw new InvalidModelOutputException("missing assistantMessage");
        if (msg.length() > 1500) msg = msg.substring(0, 1500);
        if (!n.has("updates") || !n.get("updates").isArray()) throw new InvalidModelOutputException("updates must be an array");
        if (n.get("updates").size() > 12) throw new InvalidModelOutputException("too many updates");
        java.util.List<InterviewPayload.Update> ups = new java.util.ArrayList<>();
        for (JsonNode u : n.get("updates")) {
            String id = u.path("fieldId").asText("").trim();
            if (id.isEmpty()) throw new InvalidModelOutputException("update without fieldId");
            edu.umkc.advisor.model.ResearcherMark mark = edu.umkc.advisor.model.ResearcherMark.NONE;
            String m = u.path("mark").asText("NONE").trim();
            try { mark = edu.umkc.advisor.model.ResearcherMark.valueOf(m); }
            catch (IllegalArgumentException e) { throw new InvalidModelOutputException("invalid mark"); }
            ups.add(new InterviewPayload.Update(id, u.path("text").asText(""), mark));
        }
        java.util.List<String> next = new java.util.ArrayList<>();
        if (n.has("nextFieldIds") && n.get("nextFieldIds").isArray()) for (JsonNode f : n.get("nextFieldIds")) next.add(f.asText("").trim());
        return new InterviewPayload(ups, msg, next);
    }

    public ReviewPayload parseReview(String raw) {
        JsonNode n = parseJsonObject(raw);
        if (!n.has("fields") || !n.get("fields").isArray()) throw new InvalidModelOutputException("fields must be an array");
        java.util.List<ReviewPayload.Entry> out = new java.util.ArrayList<>();
        for (JsonNode e : n.get("fields")) {
            String id = e.path("fieldId").asText("").trim();
            edu.umkc.advisor.model.FieldStatus st;
            try { st = edu.umkc.advisor.model.FieldStatus.valueOf(e.path("status").asText("").trim()); }
            catch (IllegalArgumentException ex) { continue; }   // drop malformed entries; Java fills gaps itself
            if (id.isEmpty()) continue;
            out.add(new ReviewPayload.Entry(id, st, e.path("currentInformation").asText(""),
                    e.path("missingDetails").asText(""), e.path("followUpQuestion").asText("")));
        }
        return new ReviewPayload(out);
    }

    private static <E extends Enum<E>> E enumValue(JsonNode n, String field, Class<E> type) {
        String v = n.path(field).asText("").trim();
        try {
            return Enum.valueOf(type, v);
        } catch (IllegalArgumentException e) {
            throw new InvalidModelOutputException("invalid or missing " + field);
        }
    }
}
