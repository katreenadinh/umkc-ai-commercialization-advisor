package edu.umkc.advisor.service;

import edu.umkc.advisor.model.*;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InformationStatusTest {
    private final ChecklistService c = ChecklistService.fromClasspath();

    private ChecklistService.Section section(String sectionId, Workflow w) {
        return c.sections(w).stream().filter(x -> x.section().id().equals(sectionId)).findFirst().orElseThrow();
    }

    private InformationStatus status(String sectionId, Workflow w, Map<String, FieldAnswer> answers) {
        return InformationStatus.of(section(sectionId, w).fields(), id -> answers.getOrDefault(id, FieldAnswer.EMPTY));
    }

    private Map<String, FieldAnswer> everyNote(String sectionId, Workflow w) {
        Map<String, FieldAnswer> a = new HashMap<>();
        section(sectionId, w).fields().forEach(f -> a.put(f.id(), text("Answered.")));
        return a;
    }

    private static FieldAnswer text(String t) { return new FieldAnswer(t, ResearcherMark.NONE); }
    private static FieldAnswer mark(ResearcherMark m) { return new FieldAnswer("", m); }

    @Test
    void nothingCollectedOrOnlySkippedIsNotDiscussed() {
        assertEquals(InformationStatus.NOT_DISCUSSED, status("problem", Workflow.NEW_INVENTION, Map.of()));
        assertEquals(InformationStatus.NOT_DISCUSSED, status("problem", Workflow.NEW_INVENTION, Map.of("problem_solved", mark(ResearcherMark.SKIPPED))));
    }

    @Test
    void completedOnlyWhenEveryNoteIsAnsweredOrNotApplicable() {
        Map<String, FieldAnswer> a = everyNote("problem", Workflow.NEW_INVENTION);
        assertEquals(InformationStatus.COMPLETE, status("problem", Workflow.NEW_INVENTION, a));
        a.put("problem_importance", mark(ResearcherMark.NOT_APPLICABLE));
        assertEquals(InformationStatus.COMPLETE, status("problem", Workflow.NEW_INVENTION, a));
        a.put("problem_importance", FieldAnswer.EMPTY);
        assertEquals(InformationStatus.PARTIAL, status("problem", Workflow.NEW_INVENTION, a));
        a.put("problem_importance", mark(ResearcherMark.NOT_YET_KNOWN));
        assertEquals(InformationStatus.PARTIAL, status("problem", Workflow.NEW_INVENTION, a));
    }

    @Test
    void answeringOnlyTheMainQuestionStillNeedsMoreInformation() {
        assertEquals(InformationStatus.PARTIAL, status("overview", Workflow.NEW_INVENTION,
                Map.of("summary_general", text("Ways to remove chlorine from water and which is best."))));
        assertEquals(InformationStatus.PARTIAL, status("problem", Workflow.NEW_INVENTION, Map.of("problem_who", text("Rural utilities."))));
    }

    @Test
    void askedButNotKnownIsNotYetKnownRatherThanProgress() {
        assertEquals(InformationStatus.UNKNOWN, status("differentiation", Workflow.NEW_INVENTION,
                Map.of("difference_from_others", mark(ResearcherMark.NOT_YET_KNOWN))));
        assertEquals("Not Yet Known", InformationStatus.UNKNOWN.label);
    }

    @Test
    void updateWorkflowAlsoNeedsTheExistingDisclosureNotes() {
        Map<String, FieldAnswer> a = everyNote("overview", Workflow.NEW_INVENTION);
        assertEquals(InformationStatus.COMPLETE, status("overview", Workflow.NEW_INVENTION, a));
        assertEquals(InformationStatus.PARTIAL, status("overview", Workflow.UPDATE_EXISTING, a));
    }
}
