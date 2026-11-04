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
        assertEquals(28, c.all().size());
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
        assertEquals(Set.of("evidence_testing", "development_stage", "applications_users"),
                new HashSet<>(c.all().stream().filter(f -> "PROJECT_GUIDANCE".equals(f.origin())).map(ChecklistField::id).toList()));
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
        assertEquals(List.of("summary_general", "problem_solved", "technical_description", "difference_from_others"), n.subList(0, 4));
        assertTrue(n.indexOf("public_disclosures_list") < n.indexOf("evidence_testing"));
        assertTrue(n.indexOf("evidence_testing") < n.indexOf("potential_inventors"));
        assertTrue(n.indexOf("funding") < n.indexOf("keywords"));
    }
}
