package edu.umkc.advisor.service;

import edu.umkc.advisor.exception.NotFoundException;
import edu.umkc.advisor.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static edu.umkc.advisor.service.TestKit.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftServiceTest {
    private final TestKit kit = new TestKit();
    private static final String OTHER = "99999999-8888-7777-6666-555555555555";

    private DraftService svc(ScriptedLlm llm, int max) {
        return new DraftService(kit.repo, kit.checklist, kit.reviewer(llm), new DraftSummaryBuilder(kit.checklist, kit.referral), kit.referral,
                List.of(new DraftSummaryBuilder.SourceRef("Official Source A", "https://example.edu/a")), max);
    }

    @Test
    void draftsAreIsolatedByOwnerToken() {
        var s = svc(new ScriptedLlm(), 5);
        Draft d = s.create(OWNER, Workflow.NEW_INVENTION, "Mine");
        assertThrows(NotFoundException.class, () -> s.require(d.id(), OTHER));
        assertThrows(NotFoundException.class, () -> s.updateAnswer(d.id(), OTHER, "title", "hijack", null));
        assertThrows(NotFoundException.class, () -> s.generateSummary(d.id(), OTHER));
        assertThrows(NotFoundException.class, () -> s.delete(d.id(), OTHER));
        assertTrue(s.list(OTHER).isEmpty());
        assertEquals(1, s.list(OWNER).size());
    }

    @Test
    void enforcesPerOwnerDraftLimit() {
        var s = svc(new ScriptedLlm(), 2);
        s.create(OWNER, Workflow.NEW_INVENTION, "a");
        s.create(OWNER, Workflow.NEW_INVENTION, "b");
        assertThrows(IllegalStateException.class, () -> s.create(OWNER, Workflow.NEW_INVENTION, "c"));
        s.create(OTHER, Workflow.NEW_INVENTION, "other owner is unaffected");
    }

    @Test
    void editingAnswersValidatesAndCanClear() {
        var s = svc(new ScriptedLlm(), 5);
        Draft d = s.create(OWNER, Workflow.NEW_INVENTION, "x");
        d = s.updateAnswer(d.id(), OWNER, "title", "Water sensor", null);
        assertEquals("Water sensor", d.title());
        assertEquals("Water sensor", d.answer("title").text());
        assertThrows(IllegalArgumentException.class, () -> s.updateAnswer(kit.repo.drafts.keySet().iterator().next(), OWNER, "nope", "x", null));
        String id = d.id();
        assertThrows(IllegalArgumentException.class, () -> s.updateAnswer(id, OWNER, "summary_general", "", ResearcherMark.NOT_APPLICABLE));
        assertThrows(IllegalArgumentException.class, () -> s.updateAnswer(id, OWNER, "summary_general", "x".repeat(4001), null));
        assertThrows(IllegalArgumentException.class, () -> s.updateAnswer(id, OWNER, "what_changed", "x", null));  // wrong workflow
        d = s.updateAnswer(id, OWNER, "title", "", null);
        assertFalse(d.answers().containsKey("title"));
    }

    @Test
    void editThenReviewThenSummaryAllUseTheLatestSavedAnswers() {
        ScriptedLlm llm = new ScriptedLlm(
            reviewJson(rev("problem_solved", "PARTIALLY_PROVIDED", "Contamination.", "Who is affected is unclear.", "Who is affected?")),
            reviewJson(rev("problem_solved", "PROVIDED", "Contamination affects rural wells.", "", "")));
        var s = svc(llm, 5);
        Draft d = s.create(OWNER, Workflow.NEW_INVENTION, "x");
        s.updateAnswer(d.id(), OWNER, "problem_solved", "Contamination.", null);
        var r1 = s.review(d.id(), OWNER);
        assertEquals(FieldStatus.PARTIALLY_PROVIDED, r1.fields().stream().filter(f -> f.fieldId().equals("problem_solved")).findFirst().get().status());

        s.updateAnswer(d.id(), OWNER, "problem_solved", "Contamination affects rural wells.", null);
        // summary generated BEFORE re-review must not use the stale review's follow-ups
        String stale = s.generateSummary(d.id(), OWNER);
        assertTrue(stale.contains("Contamination affects rural wells."));
        assertFalse(stale.contains("Who is affected is unclear"));

        var r2 = s.review(d.id(), OWNER);
        assertEquals(FieldStatus.PROVIDED, r2.fields().stream().filter(f -> f.fieldId().equals("problem_solved")).findFirst().get().status());
        assertTrue(s.generateSummary(d.id(), OWNER).contains("Contamination affects rural wells."));
    }

    @Test
    void editedSummaryIsSavedAndExported() throws Exception {
        var s = svc(new ScriptedLlm(), 5);
        Draft d = s.create(OWNER, Workflow.NEW_INVENTION, "x");
        assertThrows(IllegalArgumentException.class, () -> s.exportDocx(d.id(), OWNER));          // nothing generated yet
        s.generateSummary(d.id(), OWNER);
        s.saveSummary(d.id(), OWNER, "# Edited\n\nMy own edit survives export.");
        assertEquals("# Edited\n\nMy own edit survives export.", s.exportMarkdown(d.id(), OWNER));
        assertTrue(s.exportDocx(d.id(), OWNER).length > 500);
        assertThrows(IllegalArgumentException.class, () -> s.saveSummary(d.id(), OWNER, "  "));
        assertThrows(IllegalArgumentException.class, () -> s.saveSummary(d.id(), OWNER, "x".repeat(60_001)));
    }

    @Test
    void referralForSavedAnswersFlagsUpcomingDisclosure() {
        var s = svc(new ScriptedLlm(), 5);
        Draft d = s.create(OWNER, Workflow.NEW_INVENTION, "x");
        d = s.updateAnswer(d.id(), OWNER, "public_disclosures_list", "I'm giving a talk on Friday about this.", null);
        var ref = s.referralFor(d);
        assertTrue(ref.required());
        assertEquals("IMMINENT_DISCLOSURE", ref.trigger());
    }
}
