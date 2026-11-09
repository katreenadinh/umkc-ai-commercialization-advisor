package edu.umkc.advisor.service;

import edu.umkc.advisor.ai.LlmClient;
import edu.umkc.advisor.exception.LlmUnavailableException;
import edu.umkc.advisor.model.*;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static edu.umkc.advisor.service.TestKit.*;
import static org.junit.jupiter.api.Assertions.*;

class NotesOrganizerTest {
    private final TestKit kit = new TestKit();
    private static final String DEVICE = "I have made a device for caretakers and elderly people. It pings the caretakers when an elderly person needs assistance.";
    private static final String PROBLEM = "The problem is that most elderly people struggle to ask for help or do not know how to use modern technology.";

    private NotesOrganizer organizer(LlmClient llm) { return new NotesOrganizer(kit.checklist, kit.repo, llm, "system", 800); }

    private Draft conversation(Map<String, FieldAnswer> answers, String... researcher) {
        Draft d = kit.withAnswers(kit.newDraft(Workflow.NEW_INVENTION), answers);
        kit.repo.appendTurn(d.id(), Turn.assistant("To start, what have you been working on?", java.util.List.of()));
        for (String r : researcher) {
            kit.repo.appendTurn(d.id(), Turn.user(r));
            kit.repo.appendTurn(d.id(), Turn.assistant("Thanks. Tell me more.", java.util.List.of()));
        }
        return kit.repo.get(d.id());
    }

    @Test
    void summarizesAndMovesMisplacedTextToTheRightHeading() {
        Draft d = conversation(Map.of("problem_solved", text(DEVICE + "\n" + PROBLEM)), DEVICE, PROBLEM);
        var llm = new ScriptedLlm("{\"notes\":[{\"fieldId\":\"summary_general\",\"text\":\"A device that pings caretakers when an elderly person needs assistance.\"},"
                + "{\"fieldId\":\"problem_solved\",\"text\":\"Elderly people struggle to ask for help or to use modern technology.\"},"
                + "{\"fieldId\":\"problem_who\",\"text\":\"Elderly people and their caretakers.\"}]}");
        assertTrue(organizer(llm).organizeNow(d.id(), OWNER));
        Draft after = kit.repo.get(d.id());
        assertEquals("A device that pings caretakers when an elderly person needs assistance.", after.answer("summary_general").text());
        assertEquals("Elderly people struggle to ask for help or to use modern technology.", after.answer("problem_solved").text());
        assertEquals("Elderly people and their caretakers.", after.answer("problem_who").text());
        assertTrue(llm.prompts.get(0).contains("RESEARCHER: " + DEVICE));
        assertTrue(llm.prompts.get(0).contains("main_purpose | Main purpose |"));
    }

    @Test
    void ungroundedNotesAreRejectedAndOmittedHeadingsKeepTheirText() {
        Draft d = conversation(Map.of("problem_solved", text(PROBLEM), "evidence_testing", new FieldAnswer("", ResearcherMark.NOT_YET_KNOWN)), DEVICE, PROBLEM);
        var llm = new ScriptedLlm("{\"notes\":[{\"fieldId\":\"summary_general\",\"text\":\"A patented device built with Acme Health in 2024.\"},"
                + "{\"fieldId\":\"not_a_field\",\"text\":\"Caretakers.\"}]}");
        assertFalse(organizer(llm).organizeNow(d.id(), OWNER));
        Draft after = kit.repo.get(d.id());
        assertFalse(after.answer("summary_general").isAddressed());
        assertEquals(PROBLEM, after.answer("problem_solved").text());
        assertEquals(ResearcherMark.NOT_YET_KNOWN, after.answer("evidence_testing").mark());
    }

    @Test
    void anEmptyNoteClearsTextButKeepsTheResearchersMark() {
        Draft d = conversation(Map.of("problem_solved", text(DEVICE), "development_stage", new FieldAnswer(DEVICE, ResearcherMark.SKIPPED)), DEVICE);
        var llm = new ScriptedLlm("{\"notes\":[{\"fieldId\":\"problem_solved\",\"text\":\"\"},{\"fieldId\":\"development_stage\",\"text\":\"\"},"
                + "{\"fieldId\":\"summary_general\",\"text\":\"A device that pings caretakers when an elderly person needs assistance.\"}]}");
        assertTrue(organizer(llm).organizeNow(d.id(), OWNER));
        Draft after = kit.repo.get(d.id());
        assertFalse(after.answer("problem_solved").isAddressed());
        assertEquals(new FieldAnswer("", ResearcherMark.SKIPPED), after.answer("development_stage"));
        assertTrue(after.answer("summary_general").hasText());
    }

    @Test
    void fillerLikeNotMentionedNeitherFillsNorClearsAHeading() {
        Draft d = conversation(Map.of("problem_solved", text(PROBLEM)), DEVICE, PROBLEM);
        var llm = new ScriptedLlm("{\"notes\":[{\"fieldId\":\"problem_solved\",\"text\":\"Not mentioned.\"},{\"fieldId\":\"keywords\",\"text\":\"N/A\"},"
                + "{\"fieldId\":\"summary_general\",\"text\":\"A device that pings caretakers when an elderly person needs assistance.\"}]}");
        assertTrue(organizer(llm).organizeNow(d.id(), OWNER));
        Draft after = kit.repo.get(d.id());
        assertEquals(PROBLEM, after.answer("problem_solved").text());
        assertFalse(after.answer("keywords").isAddressed());
    }

    @Test
    void anAnswerAboutChallengesIsMovedOffPublicDisclosure() {
        String challenge = "Some challenges include making sure the water is 100% safe to drink";
        Draft d = conversation(Map.of("public_disclosures_list", text(challenge)), challenge);
        var llm = new ScriptedLlm("{\"notes\":[{\"fieldId\":\"public_disclosures_list\",\"text\":\"" + challenge + "\"}]}");
        assertTrue(organizer(llm).organizeNow(d.id(), OWNER));
        Draft after = kit.repo.get(d.id());
        assertEquals(challenge, after.answer("problem_challenges").text());
        assertFalse(after.answer("public_disclosures_list").isAddressed());
    }

    @Test
    void skippingAroundPlacesAnswersUnderTheTopicTheyAreAbout() {
        String testing = "We tested it using a few water samples, and it seemed to work.";
        Draft d = conversation(Map.of("funding", text(testing)), testing);
        var llm = new ScriptedLlm("{\"notes\":[{\"fieldId\":\"funding\",\"text\":\"" + testing + "\"}]}");
        assertTrue(organizer(llm).organizeNow(d.id(), OWNER));
        Draft after = kit.repo.get(d.id());
        assertTrue(after.answer("evidence_testing").hasText(), after.answer("evidence_testing").text());
        assertFalse(after.answer("funding").isAddressed());

        String grant = "We got a grant from the NSF to help us research this more.";
        Draft d2 = conversation(Map.of("public_disclosures_list", text(grant)), grant);
        var llm2 = new ScriptedLlm("{\"notes\":[{\"fieldId\":\"public_disclosures_list\",\"text\":\"" + grant + "\"}]}");
        assertTrue(organizer(llm2).organizeNow(d2.id(), OWNER));
        assertTrue(kit.repo.get(d2.id()).answer("funding").hasText());
        assertFalse(kit.repo.get(d2.id()).answer("public_disclosures_list").isAddressed());

        String how = "It uses a solar panel and a probe that reacts to the bacteria.";
        Draft d3 = conversation(Map.of("potential_inventors", text(how)), how);
        var llm3 = new ScriptedLlm("{\"notes\":[{\"fieldId\":\"potential_inventors\",\"text\":\"" + how + "\"}]}");
        assertTrue(organizer(llm3).organizeNow(d3.id(), OWNER));
        assertTrue(kit.repo.get(d3.id()).answer("technical_description").hasText());
        assertFalse(kit.repo.get(d3.id()).answer("potential_inventors").isAddressed());

        String inventors = "My graduate student and a postdoc helped build it.";
        Draft d4 = conversation(Map.of("evidence_testing", text(inventors)), inventors);
        var llm4 = new ScriptedLlm("{\"notes\":[{\"fieldId\":\"evidence_testing\",\"text\":\"" + inventors + "\"}]}");
        assertTrue(organizer(llm4).organizeNow(d4.id(), OWNER));
        assertTrue(kit.repo.get(d4.id()).answer("potential_inventors").hasText());
        assertFalse(kit.repo.get(d4.id()).answer("evidence_testing").isAddressed());

        String description = "A prototype for removing bacteria from water.";
        Draft d5 = conversation(Map.of("funding", text(description)), description);
        var llm5 = new ScriptedLlm("{\"notes\":[{\"fieldId\":\"funding\",\"text\":\"" + description + "\"}]}");
        assertTrue(organizer(llm5).organizeNow(d5.id(), OWNER));
        assertEquals(description, kit.repo.get(d5.id()).answer("summary_general").text());
        assertFalse(kit.repo.get(d5.id()).answer("funding").isAddressed());
    }

    @Test
    void aTestingNoteThatMentionsAPrototypeStaysUnderTesting() {
        String lab = "Built a prototype in the lab and it reached 98% accuracy.";
        Draft d = conversation(Map.of("evidence_testing", text(lab)), lab);
        var llm = new ScriptedLlm("{\"notes\":[{\"fieldId\":\"evidence_testing\",\"text\":\"" + lab + "\"}]}");
        organizer(llm).organizeNow(d.id(), OWNER);
        assertEquals(lab, kit.repo.get(d.id()).answer("evidence_testing").text());
        assertFalse(kit.repo.get(d.id()).answer("summary_general").hasText());
        assertFalse(kit.repo.get(d.id()).answer("development_stage").hasText());
    }

    @Test
    void anExplicitTitleGoesUnderTitleNotGeneralDescription() {
        String named = "The title for this is removing bacteria from water";
        Draft d = conversation(Map.of("summary_general", text("A prototype for removing bacteria from water.")), named);
        var llm = new ScriptedLlm("{\"notes\":[{\"fieldId\":\"summary_general\",\"text\":\"A prototype for removing bacteria from water.\"}]}");
        assertTrue(organizer(llm).organizeNow(d.id(), OWNER));
        Draft after = kit.repo.get(d.id());
        assertEquals("Removing bacteria from water", after.answer("title").text());
        assertEquals("A prototype for removing bacteria from water.", after.answer("summary_general").text());
    }

    @Test
    void notesMustMostlyUseWhatTheResearcherSaid() {
        String said = "We grow mycelium in molds shaped like containers, then bake it. We composted a few in my garden.";
        assertTrue(NotesOrganizer.supported("Mycelium is grown in container-shaped molds and then baked.", said));
        assertTrue(NotesOrganizer.supported("Composted a few samples in the researcher's garden.", said));
        assertFalse(NotesOrganizer.supported("Still in the prototype stage, with limited testing.", said));
    }

    @Test
    void sharingThatAlreadyHappenedIsMovedToPreviousDisclosures() {
        String poster = "I presented a poster about it at a campus showcase last spring.";
        Draft d = conversation(Map.of("public_disclosures_list", text(poster)), poster);
        var llm = new ScriptedLlm("{\"notes\":[{\"fieldId\":\"public_disclosures_list\",\"text\":\"Presented a poster at a campus showcase last spring.\"}]}");
        assertTrue(organizer(llm).organizeNow(d.id(), OWNER));
        Draft after = kit.repo.get(d.id());
        assertEquals("Presented a poster at a campus showcase last spring.", after.answer("previous_public_disclosures").text());
        assertFalse(after.answer("public_disclosures_list").isAddressed());

        String planned = "I will present a poster next month.";
        Draft d2 = conversation(Map.of(), planned);
        var llm2 = new ScriptedLlm("{\"notes\":[{\"fieldId\":\"public_disclosures_list\",\"text\":\"Will present a poster next month.\"}]}");
        assertTrue(organizer(llm2).organizeNow(d2.id(), OWNER));
        assertEquals("Will present a poster next month.", kit.repo.get(d2.id()).answer("public_disclosures_list").text());
    }

    @Test
    void modelFailureOrNoResearcherMessagesLeavesNotesUntouched() {
        Draft d = conversation(Map.of("problem_solved", text(PROBLEM)), PROBLEM);
        LlmClient down = (s, u, m) -> { throw new LlmUnavailableException("down"); };
        assertFalse(organizer(down).organizeNow(d.id(), OWNER));
        assertFalse(organizer(new ScriptedLlm("no json here")).organizeNow(d.id(), OWNER));
        assertEquals(PROBLEM, kit.repo.get(d.id()).answer("problem_solved").text());

        var llm = new ScriptedLlm();
        Draft empty = kit.newDraft(Workflow.NEW_INVENTION);
        assertFalse(organizer(llm).organizeNow(empty.id(), OWNER));
        assertEquals(0, llm.calls());
    }

    @Test
    void placeholderNotesAreDropped() {
        assertTrue(NotesOrganizer.isPlaceholderNote("Not specified in the conversation."));
        assertTrue(NotesOrganizer.isPlaceholderNote("Not specified, but implied importance of detecting bacteria."));
        assertFalse(NotesOrganizer.isPlaceholderNote("A special material reacts when certain bacteria are present."));
        Draft d = conversation(Map.of("problem_solved", text(PROBLEM)), DEVICE, PROBLEM);
        var llm = new ScriptedLlm("{\"notes\":[{\"fieldId\":\"problem_solved\",\"text\":\"Not specified in the conversation.\"},"
                + "{\"fieldId\":\"summary_general\",\"text\":\"A device that pings caretakers when an elderly person needs assistance.\"}]}");
        organizer(llm).organizeNow(d.id(), OWNER);
        Draft after = kit.repo.get(d.id());
        assertEquals(PROBLEM, after.answer("problem_solved").text());
        assertEquals("A device that pings caretakers when an elderly person needs assistance.", after.answer("summary_general").text());
    }

    @Test
    void coachExamplesAreNotCopiedIntoNotes() {
        String said = "It uses a special material that reacts when certain bacteria are present.";
        Draft d = conversation(Map.of("technical_description", text(said)), said);
        kit.repo.appendTurn(d.id(), Turn.assistant(
                "When that effect happens, how can you tell? For example, is there a visible change, a signal, or a measurement?",
                java.util.List.of()));
        var llm = new ScriptedLlm("{\"notes\":[{\"fieldId\":\"technical_description\","
                + "\"text\":\"The device uses a special material that reacts when certain bacteria are present, producing a visible change.\"}]}");
        organizer(llm).organizeNow(d.id(), OWNER);
        assertFalse(kit.repo.get(d.id()).answer("technical_description").text().toLowerCase().contains("visible change"));
    }

    @Test
    void aDeviceDescriptionIsNotStoredAsTheProblem() {
        String said = "I'm developing a small device that can detect harmful bacteria in drinking water.";
        Draft d = conversation(Map.of(), said);
        var llm = new ScriptedLlm("{\"notes\":[{\"fieldId\":\"summary_general\",\"text\":\"A small device that detects harmful bacteria in drinking water.\"},"
                + "{\"fieldId\":\"problem_solved\",\"text\":\"Harmful bacteria in drinking water.\"}]}");
        organizer(llm).organizeNow(d.id(), OWNER);
        assertTrue(kit.repo.get(d.id()).answer("summary_general").hasText());
        assertFalse(kit.repo.get(d.id()).answer("problem_solved").hasText());
    }

    @Test
    void nearDuplicateSentencesAreCollapsed() {
        String collapsed = NotesOrganizer.dedupeSentences(
                "A small device that detects harmful bacteria in drinking water using a special material. "
                        + "An idea for a device that detects harmful bacteria in drinking water using a special material. "
                        + "The researcher is developing the device.");
        assertFalse(collapsed.contains("An idea for a device"), collapsed);
        assertTrue(collapsed.contains("A small device that detects harmful bacteria"), collapsed);
        assertEquals(2, collapsed.split("(?<=[.!?])\\s+").length);
    }
}
