package edu.umkc.advisor.service;

import edu.umkc.advisor.ai.RelevanceResult;
import edu.umkc.advisor.dto.AskResponse;
import edu.umkc.advisor.dto.AskResponse.AnswerType;
import edu.umkc.advisor.dto.Citation;
import edu.umkc.advisor.model.NextAction;
import edu.umkc.advisor.safety.OutputGuard;
import edu.umkc.advisor.safety.ReferralDecision;
import edu.umkc.advisor.safety.ReferralService;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates one question: mandatory Java rules first, then LLM relevance, then grounded answer, then output guard.
 * Referral decisions never depend on the answer-generation prompt.
 */
public final class AdvisorService {

    static final String OUT_OF_SCOPE_MESSAGE =
            "That looks outside what I can help with. I'm here for questions about intellectual property, invention "
          + "disclosures, and commercializing research at UMKC. Feel free to ask something like \"What is prior art?\" "
          + "or \"What should I include in an invention disclosure?\"";

    private final ReferralService referral;
    private final RelevanceService relevance;
    private final AnswerGenerator answers;
    private final OutputGuard guard = new OutputGuard();

    public AdvisorService(ReferralService referral, RelevanceService relevance, AnswerGenerator answers) {
        this.referral = referral;
        this.relevance = relevance;
        this.answers = answers;
    }

    public AskResponse ask(String question) {
        ReferralDecision rule = referral.evaluate(question);

        // 1. Mandatory rules short-circuit before any LLM call.
        if (rule.blockLlm()) {
            AnswerType type = rule.primary() == ReferralDecision.Trigger.IMMINENT_DISCLOSURE
                    ? AnswerType.REFERRAL : AnswerType.BLOCKED;
            return new AskResponse(type, rule.blockingMessage(), List.of(),
                    new AskResponse.Referral(type == AnswerType.REFERRAL, rule.primary().name(),
                            type == AnswerType.REFERRAL ? rule.blockingMessage() : null),
                    null, false, AskResponse.DISCLAIMER);
        }

        // 2. LLM relevance check (validated JSON).
        RelevanceResult rel = relevance.classify(question);
        AskResponse.Relevance relInfo = new AskResponse.Relevance(rel.category().name(), rel.nextAction().name(), rel.reason());
        boolean referralNeeded = rule.referralRequired() || rel.humanReferralNeeded();
        String note = rule.referralNote() != null ? rule.referralNote()
                : rel.humanReferralNeeded() ? referral.genericReferralNote() : null;
        AskResponse.Referral refInfo = new AskResponse.Referral(referralNeeded, rule.primary().name(), note);

        NextAction action = rel.nextAction();
        // Casual "what is public disclosure?" wording is already in-scope. Do not stall on a vague-routing guess.
        if (action == NextAction.ASK_CLARIFYING_QUESTION
                && DisclosureInterviewService.looksLikeGeneralQuestion(question)) {
            action = NextAction.ANSWER_WITH_RAG;
        }
        switch (action) {
            case DECLINE_OUT_OF_SCOPE:
                return new AskResponse(AnswerType.OUT_OF_SCOPE, OUT_OF_SCOPE_MESSAGE, List.of(),
                        new AskResponse.Referral(false, "NONE", null), relInfo, false, AskResponse.DISCLAIMER);
            case ASK_CLARIFYING_QUESTION:
                return new AskResponse(AnswerType.CLARIFICATION, rel.clarifyingQuestion(), List.of(), refInfo, relInfo,
                        false, AskResponse.DISCLAIMER);
            case REFER_TO_STAFF:
                return new AskResponse(AnswerType.REFERRAL, note != null ? note : referral.genericReferralNote(), List.of(),
                        new AskResponse.Referral(true, rule.primary().name(), note), relInfo, false, AskResponse.DISCLAIMER);
            default:
                break;
        }

        // 3. Grounded answer, then output guard, then mandatory referral note appended by Java.
        AnswerGenerator.GeneratedAnswer ans = answers.generate(question);
        Set<String> allowed = new HashSet<>();
        for (Citation c : ans.citations()) if (c.url() != null) allowed.add(c.url());
        ReferralService.Contact contact = referral.contact();
        if (contact.url() != null) allowed.add(contact.url());
        if (contact.email() != null) allowed.add(contact.email());

        OutputGuard.Result checked = guard.scan(ans.text(), allowed);
        String text = checked.text();
        List<Citation> cites = checked.violated() ? List.of() : ans.citations();
        if (note != null) text = text + "\n\n" + note;
        return new AskResponse(AnswerType.ANSWER, text, cites, refInfo, relInfo,
                ans.insufficientEvidence() || checked.violated(), AskResponse.DISCLAIMER);
    }
}
