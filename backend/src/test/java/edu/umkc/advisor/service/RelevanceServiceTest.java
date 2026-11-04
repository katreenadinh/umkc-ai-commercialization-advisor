package edu.umkc.advisor.service;

import edu.umkc.advisor.ai.LlmClient;
import edu.umkc.advisor.ai.RelevanceResult;
import edu.umkc.advisor.ai.ResponseValidator;
import edu.umkc.advisor.model.NextAction;
import edu.umkc.advisor.model.RelevanceCategory;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

class RelevanceServiceTest {

    private static final class Fake implements LlmClient {
        final Deque<String> replies = new ArrayDeque<>();
        int calls = 0;
        Fake(String... r) { for (String s : r) replies.add(s); }
        public String complete(String s, String u, int m) { calls++; return replies.poll(); }
    }

    private static String json(String cat, String act, String conf) {
        return "{\"category\":\"" + cat + "\",\"nextAction\":\"" + act + "\",\"reason\":\"r\",\"humanReferralNeeded\":false,"
             + "\"clarifyingQuestion\":null,\"confidence\":" + conf + "}";
    }

    private RelevanceService svc(Fake f) { return new RelevanceService(f, new ResponseValidator(), "sys"); }

    @Test
    void retriesOnceThenSucceeds() {
        Fake f = new Fake("garbage", json("IP_GENERAL", "ANSWER_WITH_RAG", "0.9"));
        RelevanceResult r = svc(f).classify("What is prior art?");
        assertEquals(RelevanceCategory.IP_GENERAL, r.category());
        assertEquals(2, f.calls);
    }

    @Test
    void failsSafeToClarificationNeverRejection() {
        RelevanceResult r = svc(new Fake("bad", "worse")).classify("my lab built a thing");
        assertEquals(RelevanceCategory.UNCERTAIN, r.category());
        assertEquals(NextAction.ASK_CLARIFYING_QUESTION, r.nextAction());
    }

    @Test
    void lowConfidenceOutOfScopeIsDowngraded() {
        RelevanceResult r = svc(new Fake(json("OUT_OF_SCOPE", "DECLINE_OUT_OF_SCOPE", "0.6"))).classify("can i make money from my code?");
        assertEquals(RelevanceCategory.UNCERTAIN, r.category());
        assertNotNull(r.clarifyingQuestion());
    }

    @Test
    void confidentOutOfScopeIsDeclined() {
        RelevanceResult r = svc(new Fake(json("OUT_OF_SCOPE", "DECLINE_OUT_OF_SCOPE", "0.97"))).classify("best pizza in KC?");
        assertEquals(NextAction.DECLINE_OUT_OF_SCOPE, r.nextAction());
    }
}
