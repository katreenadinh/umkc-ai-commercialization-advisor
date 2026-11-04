package edu.umkc.advisor.service;

import edu.umkc.advisor.ai.LlmClient;
import edu.umkc.advisor.ai.RelevanceResult;
import edu.umkc.advisor.ai.ResponseValidator;
import edu.umkc.advisor.exception.InvalidModelOutputException;
import edu.umkc.advisor.model.NextAction;
import edu.umkc.advisor.model.RelevanceCategory;

/** LLM-based relevance check with schema validation, one retry, and a fail-safe that never silently rejects. */
public final class RelevanceService {

    /** Below this confidence, OUT_OF_SCOPE is downgraded to UNCERTAIN so legitimate questions are not rejected. */
    public static final double OUT_OF_SCOPE_MIN_CONFIDENCE = 0.75;

    private final LlmClient llm;
    private final ResponseValidator validator;
    private final String systemPrompt;

    public RelevanceService(LlmClient llm, ResponseValidator validator, String systemPrompt) {
        this.llm = llm;
        this.validator = validator;
        this.systemPrompt = systemPrompt;
    }

    public RelevanceResult classify(String question) {
        String user = "<user_message>\n" + question + "\n</user_message>";
        RelevanceResult result;
        try {
            result = validator.parseRelevance(llm.complete(systemPrompt, user, 300));
        } catch (InvalidModelOutputException first) {
            try {
                String retry = user + "\n\nYour previous reply was not valid JSON for the schema. Return only the JSON object.";
                result = validator.parseRelevance(llm.complete(systemPrompt, retry, 300));
            } catch (InvalidModelOutputException second) {
                return new RelevanceResult(RelevanceCategory.UNCERTAIN, NextAction.ASK_CLARIFYING_QUESTION,
                        "Routing output could not be validated; asking for clarification.", false,
                        ResponseValidator.GENERIC_CLARIFICATION, 0.0);
            }
        }
        if (result.category() == RelevanceCategory.OUT_OF_SCOPE && result.confidence() < OUT_OF_SCOPE_MIN_CONFIDENCE) {
            return new RelevanceResult(RelevanceCategory.UNCERTAIN, NextAction.ASK_CLARIFYING_QUESTION,
                    "Low-confidence out-of-scope result downgraded to uncertain.", result.humanReferralNeeded(),
                    ResponseValidator.GENERIC_CLARIFICATION, result.confidence());
        }
        return result;
    }
}
