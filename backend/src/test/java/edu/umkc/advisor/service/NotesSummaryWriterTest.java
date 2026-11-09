package edu.umkc.advisor.service;

import edu.umkc.advisor.model.*;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static edu.umkc.advisor.service.TestKit.*;
import static org.junit.jupiter.api.Assertions.*;

class NotesSummaryWriterTest {
    private final TestKit kit = new TestKit();

    @Test
    void groundingRejectsAddedNumbersNamesAndJudgements() {
        String src = "- Problem: Rural utilities wait days for lab results on lead in water.\n";
        assertTrue(NotesSummaryWriter.grounded("Rural utilities wait days for lead results. The researcher wants results sooner.", src));
        assertFalse(NotesSummaryWriter.grounded("Utilities wait 3 days for results.", src));
        assertFalse(NotesSummaryWriter.grounded("The researcher worked with Acme on this.", src));
        assertFalse(NotesSummaryWriter.grounded("It is a novel approach to lead testing.", src));
        assertFalse(NotesSummaryWriter.grounded("This is the best way to test water.", src));
        assertFalse(NotesSummaryWriter.grounded("It is cheaper and more reliable than lab testing.", src));
        assertTrue(NotesSummaryWriter.grounded("The researcher worked with Acme.", src + "- Collaborators: acme labs\n"));
    }

    @Test
    void onlySectionsWithWrittenAnswersAreSentAndRepeatsAreCached() {
        var llm = new ScriptedLlm("Sure! {\"sections\":[{\"id\":\"problem\",\"summary\":\"Rural utilities wait days for lab results.\"},"
                + "{\"id\":\"inventors\",\"summary\":\"Ana did it.\"}]}");
        var writer = new NotesSummaryWriter(llm, "system", 500);
        Draft d = kit.withAnswers(kit.newDraft(Workflow.NEW_INVENTION), Map.of("problem_solved", text("Rural utilities wait days for lab results."),
                "funding", new FieldAnswer("", ResearcherMark.NOT_APPLICABLE)));
        var sections = kit.checklist.sections(Workflow.NEW_INVENTION);
        assertEquals(Map.of("problem", "Rural utilities wait days for lab results."), writer.write(d, sections));
        assertTrue(llm.prompts.get(0).contains("<section id=\"problem\""));
        assertFalse(llm.prompts.get(0).contains("<section id=\"funding_support\""));
        assertEquals(Map.of("problem", "Rural utilities wait days for lab results."), writer.write(d, sections));
        assertEquals(1, llm.calls());
    }

    @Test
    void noAnswersMeansNoModelCallAndBadOutputMeansNoSummaries() {
        var llm = new ScriptedLlm("not json at all");
        var writer = new NotesSummaryWriter(llm, "system", 500);
        assertEquals(Map.of(), writer.write(kit.newDraft(Workflow.NEW_INVENTION), kit.checklist.sections(Workflow.NEW_INVENTION)));
        assertEquals(0, llm.calls());
        Draft d = kit.withAnswers(kit.newDraft(Workflow.NEW_INVENTION), Map.of("summary_general", text("A water sensor.")));
        assertEquals(Map.of(), writer.write(d, kit.checklist.sections(Workflow.NEW_INVENTION)));
    }
}
