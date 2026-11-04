package edu.umkc.advisor.ai;

import edu.umkc.advisor.exception.InvalidModelOutputException;
import edu.umkc.advisor.model.NextAction;
import edu.umkc.advisor.model.RelevanceCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResponseValidatorTest {
    private final ResponseValidator v = new ResponseValidator();

    private static String json(String cat, String action, boolean ref, String q, String conf) {
        return "{\"category\":\"" + cat + "\",\"nextAction\":\"" + action + "\",\"reason\":\"r\",\"humanReferralNeeded\":" + ref
             + ",\"clarifyingQuestion\":" + (q == null ? "null" : "\"" + q + "\"") + ",\"confidence\":" + conf + "}";
    }

    @Test
    void parsesValidJsonInsideCodeFence() {
        RelevanceResult r = v.parseRelevance("```json\n" + json("IP_GENERAL", "ANSWER_WITH_RAG", false, null, "0.9") + "\n```");
        assertEquals(RelevanceCategory.IP_GENERAL, r.category());
        assertEquals(NextAction.ANSWER_WITH_RAG, r.nextAction());
    }

    @Test
    void rejectsMalformedAndUnknownEnums() {
        assertThrows(InvalidModelOutputException.class, () -> v.parseRelevance("not json"));
        assertThrows(InvalidModelOutputException.class, () -> v.parseRelevance(json("BANANA", "ANSWER_WITH_RAG", false, null, "0.9")));
        assertThrows(InvalidModelOutputException.class, () -> v.parseRelevance(json("IP_GENERAL", "DO_STUFF", false, null, "0.9")));
        assertThrows(InvalidModelOutputException.class, () -> v.parseRelevance(json("IP_GENERAL", "ANSWER_WITH_RAG", false, null, "1.7")));
        assertThrows(InvalidModelOutputException.class, () -> v.parseRelevance("{\"category\":\"IP_GENERAL\"}"));
    }

    @Test
    void categoryIsAuthoritativeOverAction() {
        RelevanceResult oos = v.parseRelevance(json("OUT_OF_SCOPE", "ANSWER_WITH_RAG", false, null, "0.95"));
        assertEquals(NextAction.DECLINE_OUT_OF_SCOPE, oos.nextAction());
        RelevanceResult unc = v.parseRelevance(json("UNCERTAIN", "ANSWER_WITH_RAG", false, null, "0.4"));
        assertEquals(NextAction.ASK_CLARIFYING_QUESTION, unc.nextAction());
        assertNotNull(unc.clarifyingQuestion());
        RelevanceResult exp = v.parseRelevance(json("EXPERT_REVIEW", "ANSWER_WITH_RAG", false, null, "0.9"));
        assertTrue(exp.humanReferralNeeded());
        RelevanceResult ts = v.parseRelevance(json("TIME_SENSITIVE", "ANSWER_WITH_RAG", false, null, "0.9"));
        assertTrue(ts.humanReferralNeeded());
    }

    @Test
    void parsesAndValidatesAnswerPayload() {
        AnswerPayload a = v.parseAnswer("{\"answer\":\"Hi [S1]\",\"usedSources\":[\"S1\"],\"sufficientEvidence\":true,\"needsStaffFollowUp\":false}");
        assertEquals(java.util.List.of("S1"), a.usedSources());
        assertThrows(InvalidModelOutputException.class, () -> v.parseAnswer("{\"answer\":\"\",\"usedSources\":[],\"sufficientEvidence\":true,\"needsStaffFollowUp\":false}"));
        assertThrows(InvalidModelOutputException.class, () -> v.parseAnswer("{\"answer\":\"x\",\"usedSources\":\"S1\",\"sufficientEvidence\":true,\"needsStaffFollowUp\":false}"));
        assertThrows(InvalidModelOutputException.class, () -> v.parseAnswer("{\"answer\":\"x\",\"usedSources\":[],\"sufficientEvidence\":\"yes\",\"needsStaffFollowUp\":false}"));
    }
}
