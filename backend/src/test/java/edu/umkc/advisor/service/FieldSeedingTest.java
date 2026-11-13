package edu.umkc.advisor.service;

import edu.umkc.advisor.model.FieldAnswer;
import edu.umkc.advisor.model.ResearcherMark;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FieldSeedingTest {

    @Test
    void splitsDepartmentsAndContributionsOutOfInventorWording() {
        Map<String, FieldAnswer> answers = new LinkedHashMap<>();
        String said = "Ana from the Chemistry department. Ana did the electronics and Bo wrote the software.";
        answers.put("potential_inventors", new FieldAnswer(said, ResearcherMark.NONE));
        List<String> changed = FieldSeeding.apply(answers, said);
        assertTrue(changed.contains("inventor_departments"));
        assertTrue(changed.contains("inventor_contributions"));
        assertTrue(answers.get("inventor_departments").text().toLowerCase().contains("chemistry"));
        assertTrue(answers.get("inventor_contributions").text().contains("Ana"));
        assertTrue(answers.get("inventor_contributions").text().contains("Bo"));
    }

    @Test
    void doesNotOverwriteAHeadingThatAlreadyHasText() {
        Map<String, FieldAnswer> answers = new LinkedHashMap<>();
        answers.put("inventor_departments", new FieldAnswer("School of Computing", ResearcherMark.NONE));
        FieldSeeding.apply(answers, "Ana from the Chemistry department.");
        assertEquals("School of Computing", answers.get("inventor_departments").text());
    }

    @Test
    void extractsGrantNumbers() {
        Map<String, FieldAnswer> answers = new LinkedHashMap<>();
        FieldSeeding.apply(answers, "It was funded by NSF grant number DMR-2134567.");
        assertEquals("DMR-2134567", answers.get("grant_numbers").text());
    }
}
