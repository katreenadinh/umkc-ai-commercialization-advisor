package edu.umkc.advisor.service;

import edu.umkc.advisor.model.ChecklistField;
import edu.umkc.advisor.model.Workflow;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ChecklistServiceTest {
    private final ChecklistService c = ChecklistService.fromClasspath();

    @Test
    void loadsAndRecordsHonestInspectionStatus() {
        assertEquals(55, c.all().size());
        assertEquals(8, c.coach(Workflow.NEW_INVENTION).size());
        assertEquals(10, c.coach(Workflow.UPDATE_EXISTING).size());
        assertTrue(c.formVersion().contains("UM 16C"));
        assertTrue(c.inspectionStatus().startsWith("PARTIAL"));
        Set<String> ids = new HashSet<>();
        c.all().forEach(f -> assertTrue(ids.add(f.id()), "duplicate " + f.id()));
    }

    @Test
    void projectGuidanceIsNeverPresentedAsOfficial() {
        for (ChecklistField f : c.all()) {
            if ("PROJECT_GUIDANCE".equals(f.origin())) { assertFalse(f.verified()); assertFalse(f.officialFormField()); }
            else assertTrue(f.verified() && f.sourceRef() != null, f.id());
        }
        Set<String> guidance = new HashSet<>(c.all().stream().filter(f -> "PROJECT_GUIDANCE".equals(f.origin())).map(ChecklistField::id).toList());
        assertTrue(guidance.containsAll(Set.of("evidence_testing", "development_stage", "applications_users", "main_purpose", "grant_numbers")));
        assertFalse(guidance.contains("funding"));
    }

    @Test
    void everyNoteBelongsToOneOfTheNineSectionsExceptTheTwoFormOnlyItems() {
        List<String> titles = c.sections(Workflow.NEW_INVENTION).stream().map(s -> s.section().title()).toList();
        assertEquals(List.of("Invention Overview", "Problem", "How It Works", "Differentiation", "Public Disclosure",
                "Development and Evidence", "Applications", "Inventors", "Funding and Outside Support"), titles);
        for (Workflow w : Workflow.values()) {
            Set<String> sectioned = new HashSet<>();
            c.sections(w).forEach(s -> s.fields().forEach(f -> assertTrue(sectioned.add(f.id()), f.id())));
            Set<String> outside = new HashSet<>(c.applicable(w).stream().map(ChecklistField::id).toList());
            outside.removeAll(sectioned);
            assertEquals(Set.of("export_control", "conflicts_of_interest"), outside, w.name());
        }
        var overview = c.sections(Workflow.UPDATE_EXISTING).get(0).fields().stream().map(ChecklistField::id).toList();
        assertEquals(List.of("existing_disclosure_ref", "what_changed"), overview.subList(0, 2));
        assertFalse(c.sections(Workflow.NEW_INVENTION).get(0).fields().stream().anyMatch(f -> f.id().equals("what_changed")));
    }

    @Test
    void newHeadingsSortAfterEveryOriginalNote() {
        List<ChecklistField> n = c.applicable(Workflow.NEW_INVENTION);
        int firstNew = n.indexOf(c.find("main_purpose").orElseThrow());
        assertTrue(n.subList(0, firstNew).stream().allMatch(f -> f.priority() <= 3));
        assertTrue(n.subList(firstNew, n.size()).stream().allMatch(f -> f.priority() == 4 && !f.inCoach()));
    }

    @Test
    void workflowsSelectApplicableFields() {
        List<String> n = c.applicable(Workflow.NEW_INVENTION).stream().map(ChecklistField::id).toList();
        List<String> u = c.applicable(Workflow.UPDATE_EXISTING).stream().map(ChecklistField::id).toList();
        assertFalse(n.contains("what_changed"));
        assertTrue(u.containsAll(List.of("existing_disclosure_ref", "what_changed")));
        assertEquals(n.size() + 2, u.size());
    }

    @Test
    void interviewPriorityFollowsTheBriefedOrder() {
        List<String> n = c.applicable(Workflow.NEW_INVENTION).stream().map(ChecklistField::id).toList();
        List<String> coach = c.coach(Workflow.NEW_INVENTION).stream().map(ChecklistField::id).toList();
        assertEquals(List.of("summary_general", "problem_solved", "technical_description", "difference_from_others"), n.subList(0, 4));
        assertEquals(List.of("summary_general", "problem_solved", "technical_description", "difference_from_others"), coach.subList(0, 4));
        assertTrue(coach.indexOf("public_disclosures_list") < coach.indexOf("evidence_testing"));
        assertTrue(coach.contains("potential_inventors"));
        assertTrue(coach.contains("funding"));
        assertFalse(coach.contains("keywords"));
        assertTrue(n.indexOf("funding") < n.indexOf("keywords"));
    }
}
