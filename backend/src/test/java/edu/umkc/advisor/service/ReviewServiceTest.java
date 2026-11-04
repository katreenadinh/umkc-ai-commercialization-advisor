package edu.umkc.advisor.service;

import edu.umkc.advisor.exception.LlmUnavailableException;
import edu.umkc.advisor.model.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static edu.umkc.advisor.service.DisclosureReviewService.*;
import static edu.umkc.advisor.service.TestKit.*;
import static org.junit.jupiter.api.Assertions.*;

class ReviewServiceTest {
    private final TestKit kit = new TestKit();

    private static FieldReview f(ReviewResult r, String id) { return r.fields().stream().filter(x -> x.fieldId().equals(id)).findFirst().orElseThrow(); }

    @Test
    void emptyDraftIsAllMissingWithoutCallingTheModel() {
        ScriptedLlm llm = new ScriptedLlm();
        ReviewResult r = kit.reviewer(llm).review(kit.newDraft(Workflow.NEW_INVENTION));
        assertEquals(0, llm.calls());
        assertTrue(r.fields().stream().allMatch(x -> x.status() == FieldStatus.MISSING));
        assertEquals(0, r.progress().addressed());
        assertEquals(0, r.progress().percent());
        assertTrue(r.disclaimer().contains("nothing about patentability"));
    }

    @Test
    void partiallyProvidedTestingMatchesTheExampleInTheBrief() {
        ScriptedLlm llm = new ScriptedLlm(reviewJson(rev("evidence_testing", "PARTIALLY_PROVIDED",
                "The researcher built a prototype and tested it in the laboratory.",
                "The test conditions, measurements, and results have not been described.",
                "What results have you observed so far, and how did you test the prototype?")));
        Draft d = kit.newDraft(Workflow.NEW_INVENTION);
        d = kit.withAnswers(d, Map.of("evidence_testing", text("We built a prototype and tested it in the laboratory.")));
        FieldReview x = f(kit.reviewer(llm).review(d), "evidence_testing");
        assertEquals(FieldStatus.PARTIALLY_PROVIDED, x.status());
        assertTrue(x.missingDetails().contains("results"));
        assertTrue(x.followUpQuestion().endsWith("?"));
        assertFalse(x.officialFormField());                               // helpful extra, not an official form field
    }

    @Test
    void modelCannotInventResultsOrUseEvaluativeLanguage() {
        ScriptedLlm llm = new ScriptedLlm(reviewJson(
            rev("evidence_testing", "PROVIDED", "The prototype achieved 95% accuracy in lab trials.", "", ""),
            rev("novel_aspects", "PARTIALLY_PROVIDED", "The researcher thinks the coating is different.", "This idea lacks novelty and is weak.", "Is it novel?")));
        Draft d = kit.withAnswers(kit.newDraft(Workflow.NEW_INVENTION), Map.of(
                "evidence_testing", text("We tested the prototype in the lab."),
                "novel_aspects", text("I think the coating is different.")));
        ReviewResult r = kit.reviewer(llm).review(d);
        assertEquals("We tested the prototype in the lab.", f(r, "evidence_testing").currentInformation());   // invented figure replaced
        FieldReview n = f(r, "novel_aspects");
        assertFalse(n.missingDetails().toLowerCase().contains("lacks novelty"));
        assertFalse(n.missingDetails().toLowerCase().contains("weak"));
    }

    @Test
    void modelCannotClaimMissingUnknownOrNotApplicableForTextThatExists() {
        ScriptedLlm llm = new ScriptedLlm(reviewJson(
            rev("summary_general", "NOT_APPLICABLE", "A sensor for water.", "", ""),
            rev("problem_solved", "MISSING", "Contaminated water.", "", "What problem?"),
            rev("technical_description", "NOT_YET_KNOWN", "Conductivity.", "", "")));
        Draft d = kit.withAnswers(kit.newDraft(Workflow.NEW_INVENTION), Map.of(
                "summary_general", text("A sensor for water."), "problem_solved", text("Contaminated water."), "technical_description", text("Conductivity.")));
        ReviewResult r = kit.reviewer(llm).review(d);
        for (String id : List.of("summary_general", "problem_solved", "technical_description"))
            assertEquals(FieldStatus.PARTIALLY_PROVIDED, f(r, id).status(), id);
    }

    @Test
    void researcherMarksDriveStatusesWithoutTheModel() {
        ScriptedLlm llm = new ScriptedLlm();
        Map<String, FieldAnswer> a = new HashMap<>();
        a.put("evidence_testing", new FieldAnswer("", ResearcherMark.NOT_YET_KNOWN));
        a.put("funding", new FieldAnswer("No outside funding", ResearcherMark.NOT_APPLICABLE));
        a.put("summary_general", new FieldAnswer("", ResearcherMark.NOT_APPLICABLE));   // not allowed to be N/A
        a.put("keywords", new FieldAnswer("", ResearcherMark.SKIPPED));
        ReviewResult r = kit.reviewer(llm).review(kit.withAnswers(kit.newDraft(Workflow.NEW_INVENTION), a));
        assertEquals(0, llm.calls());
        assertEquals(FieldStatus.NOT_YET_KNOWN, f(r, "evidence_testing").status());
        assertEquals(FieldStatus.NOT_APPLICABLE, f(r, "funding").status());
        assertEquals(FieldStatus.NEEDS_HUMAN_REVIEW, f(r, "summary_general").status());
        assertEquals(FieldStatus.MISSING, f(r, "keywords").status());
        assertTrue(f(r, "keywords").missingDetails().contains("Skipped"));
    }

    @Test
    void progressExcludesNotApplicableAndOnlyCountsAddressedSections() {
        ScriptedLlm llm = new ScriptedLlm(reviewJson(rev("summary_general", "PROVIDED", "A water sensor.", "", ""),
                rev("problem_solved", "PARTIALLY_PROVIDED", "Contamination.", "Who is affected is unclear.", "Who is affected?")));
        Map<String, FieldAnswer> a = new HashMap<>();
        a.put("summary_general", text("A water sensor."));
        a.put("problem_solved", text("Contamination."));
        a.put("funding", new FieldAnswer("", ResearcherMark.NOT_APPLICABLE));
        a.put("third_party", new FieldAnswer("", ResearcherMark.NOT_APPLICABLE));
        a.put("evidence_testing", new FieldAnswer("", ResearcherMark.NOT_YET_KNOWN));
        ReviewResult r = kit.reviewer(llm).review(kit.withAnswers(kit.newDraft(Workflow.NEW_INVENTION), a));
        int total = kit.checklist.applicable(Workflow.NEW_INVENTION).size();
        assertEquals(2, r.progress().notApplicable());
        assertEquals(total - 2, r.progress().applicable());
        assertEquals(2, r.progress().addressed());              // provided + not-yet-known (partial is not "complete")
        assertEquals(1, r.progress().partial());
        assertEquals(Math.round(100.0 * 2 / (total - 2)), r.progress().percent());
    }

    @Test
    void editingOneAnswerReReviewsOnlyThatAnswer() {
        ScriptedLlm llm = new ScriptedLlm(
            reviewJson(rev("summary_general", "PROVIDED", "A water sensor.", "", ""), rev("problem_solved", "PARTIALLY_PROVIDED", "Contamination.", "Who is affected is unclear.", "Who is affected?")),
            reviewJson(rev("problem_solved", "PROVIDED", "Contamination affects rural wells.", "", "")));
        var svc = kit.reviewer(llm);
        Draft d = kit.withAnswers(kit.newDraft(Workflow.NEW_INVENTION), Map.of("summary_general", text("A water sensor."), "problem_solved", text("Contamination.")));
        ReviewResult first = svc.review(d);
        kit.repo.saveReview(d.id(), svc.toJson(first));
        assertEquals(1, llm.calls());

        // unchanged draft: served from cache, no new model call
        Draft same = kit.repo.get(d.id());
        assertTrue(svc.isFresh(same, svc.parse(same.reviewJson())));
        svc.review(same);
        assertEquals(1, llm.calls());

        // edit one answer: only that field goes to the model, and its status updates
        Draft edited = kit.withAnswers(same, Map.of("summary_general", text("A water sensor."), "problem_solved", text("Contamination affects rural wells.")));
        assertFalse(svc.isFresh(edited, svc.parse(edited.reviewJson())));
        ReviewResult second = svc.review(edited);
        assertEquals(2, llm.calls());
        String prompt = llm.prompts.get(1);
        assertTrue(prompt.contains("id=\"problem_solved\""));
        assertFalse(prompt.contains("id=\"summary_general\""));
        assertEquals(FieldStatus.PROVIDED, f(second, "problem_solved").status());
        assertEquals(FieldStatus.PROVIDED, f(second, "summary_general").status());
    }

    @Test
    void modelFailureDegradesGracefullyAndNeverInventsAStatus() {
        Draft d = kit.withAnswers(kit.newDraft(Workflow.NEW_INVENTION), Map.of("summary_general", text("A water sensor.")));
        ReviewResult bad = kit.reviewer(new ScriptedLlm("junk", "junk")).review(d);
        assertFalse(bad.automaticReviewComplete());
        assertEquals(FieldStatus.NEEDS_HUMAN_REVIEW, f(bad, "summary_general").status());
        assertEquals("A water sensor.", f(bad, "summary_general").currentInformation());
        assertEquals(FieldStatus.MISSING, f(bad, "problem_solved").status());       // Java-decided fields still returned

        var down = new edu.umkc.advisor.ai.LlmClient() { public String complete(String s, String u, int m) { throw new LlmUnavailableException("down"); } };
        ReviewResult r2 = new DisclosureReviewService(kit.checklist, kit.referral, down, new edu.umkc.advisor.ai.ResponseValidator(), "sys", 500).review(d);
        assertFalse(r2.automaticReviewComplete());
    }

    @Test
    void publicDisclosureInAnswersRaisesReferralInTheReview() {
        Draft d = kit.withAnswers(kit.newDraft(Workflow.NEW_INVENTION), Map.of("public_disclosures_list", text("I'm presenting a poster next week.")));
        ReviewResult r = kit.reviewer(new ScriptedLlm(reviewJson(rev("public_disclosures_list", "PARTIALLY_PROVIDED", "A poster is planned.", "The venue is not named.", "Which event is it?")))).review(d);
        assertTrue(r.referralRequired());
        assertTrue(r.referralNote().contains("office@example.edu"));
    }

    @Test
    void humanReviewNotesAreAlwaysAttachedFromTheChecklist() {
        Draft d = kit.withAnswers(kit.newDraft(Workflow.NEW_INVENTION), Map.of("potential_inventors", text("Ana ran the experiments. Bo wrote the code.")));
        ReviewResult r = kit.reviewer(new ScriptedLlm(reviewJson(rev("potential_inventors", "PARTIALLY_PROVIDED", "Ana ran experiments; Bo wrote code.", "Affiliations are not stated.", "What are their affiliations?")))).review(d);
        assertTrue(f(r, "potential_inventors").humanReviewNote().contains("legal determination"));
    }
}
