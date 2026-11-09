package edu.umkc.advisor.service;

import edu.umkc.advisor.model.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static edu.umkc.advisor.service.TestKit.*;
import static org.junit.jupiter.api.Assertions.*;

/** Summary requests, corrections, and returning to a conversation. All data is synthetic. */
class InterviewSummaryAndCorrectionTest {
    private final TestKit kit = new TestKit();

    private Draft withNotes(Map<String, FieldAnswer> answers, String lastQuestion, String... fieldIds) {
        Draft d = kit.newDraft(Workflow.NEW_INVENTION);
        kit.repo.saveAnswers(d.id(), answers);
        kit.repo.appendTurn(d.id(), Turn.assistant(lastQuestion, List.of(fieldIds)));
        return kit.repo.get(d.id());
    }

    private static FieldAnswer said(String text) { return new FieldAnswer(text, ResearcherMark.NONE); }

    @Test
    void summaryRequestsAreRecognizedInEverydayWording() {
        for (String m : List.of("Can you tell me what information you have collected so far and what I'm still missing?",
                "What have you collected so far?", "What am I still missing?", "Can you give me a recap?", "Summarize my notes please"))
            assertTrue(DisclosureInterviewService.isSummaryRequest(m), m);
        for (String m : List.of("What else do you need to know about the device?", "What is a public disclosure?"))
            assertFalse(DisclosureInterviewService.isSummaryRequest(m), m);
    }

    @Test
    void summaryListsTopicsFromTheNotesWithoutAskingAQuestionOrCallingTheModel() {
        Draft d = withNotes(Map.of(
                "summary_general", said("A device that detects harmful bacteria in drinking water."),
                "technical_description", said("A special material reacts when certain bacteria are present."),
                "potential_inventors", said("The researcher, two other graduate students, and their professor."),
                "previous_public_disclosures", said("An abstract was published last semester.")),
                "Was any outside funding involved?", "funding");
        ScriptedLlm llm = new ScriptedLlm();
        var r = kit.interview(llm).handle(d, "Can you tell me what information you have collected so far and what I'm still missing?");
        String text = r.assistantMessage();
        assertEquals(0, llm.calls());
        assertFalse(text.contains("?"), text);
        assertFalse(text.toLowerCase().contains("funding involved"), text);
        assertTrue(text.contains("**Information collected:**"), text);
        assertTrue(text.contains("- **Invention Overview:** A device that detects harmful bacteria in drinking water."), text);
        assertTrue(text.contains("- **How It Works:**"), text);
        assertTrue(text.contains("**Not discussed yet:**") && text.contains("Funding and Outside Support"), text);
        assertTrue(text.contains("**Public disclosure:**"), text);
        assertTrue(text.contains("**Inventorship:**"), text);
        assertTrue(r.askedFieldIds().isEmpty());
    }

    @Test
    void correctionsReplaceTheOldNoteAndDoNotStartANewTopic() {
        Draft d = withNotes(Map.of("summary_general", said("A water bacteria detector."),
                        "development_stage", said("We have built a working prototype.")),
                "Have you tested it with different bacteria?", "evidence_testing");
        ScriptedLlm llm = new ScriptedLlm(interviewJson(
                List.of(upd("development_stage", "we haven't built a working prototype yet. We only have a design and some initial material samples", "NONE")),
                "Got it. We can still document the design and material samples. Is there any external funding that supported your work?", "funding"));
        var r = kit.interview(llm).handle(d, "Actually, I should clarify that we haven't built a working prototype yet. We only have a design and some initial material samples.");
        assertFalse(r.assistantMessage().contains("?"), r.assistantMessage());
        assertFalse(r.assistantMessage().toLowerCase().contains("funding"), r.assistantMessage());
        assertTrue(r.askedFieldIds().isEmpty());
        String stage = kit.repo.get(d.id()).answer("development_stage").text();
        assertFalse(stage.contains("We have built a working prototype"), stage);
        assertTrue(stage.contains("initial material samples"), stage);
    }

    @Test
    void onlyClearCorrectionsSkipTheFollowUpQuestion() {
        assertTrue(DisclosureInterviewService.isCorrection("Actually, we haven't tested it yet."));
        assertTrue(DisclosureInterviewService.isCorrection("To be clear, the sensor is not wireless."));
        assertFalse(DisclosureInterviewService.isCorrection("Actually, before we continue, can you explain what commercialization means?"));
        assertFalse(DisclosureInterviewService.isCorrection("Okay, going back to my water detection device, we have built a working prototype."));
    }

    @Test
    void askingWhatElseIsNeededGetsOneQuestionAboutTheWorkNotFunding() {
        Draft d = withNotes(Map.of("summary_general", said("A water bacteria detector."),
                        "technical_description", said("A special material reacts to bacteria.")),
                "Got it. So far, you've developed a design.");
        ScriptedLlm llm = new ScriptedLlm();
        var r = kit.interview(llm).handle(d, "What else do you need to know about the device?");
        assertEquals(0, llm.calls());
        assertEquals(List.of("difference_from_others"), r.askedFieldIds());
        assertTrue(r.assistantMessage().startsWith("One thing that would help: "), r.assistantMessage());
        assertTrue(r.assistantMessage().endsWith(kit.checklist.find("difference_from_others").get().starterQuestion()));
    }

    @Test
    void returningResumesWithTheQuestionThatWasStillOpen() {
        Draft d = withNotes(Map.of("summary_general", said("A water bacteria detector."),
                        "technical_description", said("A special material reacts to bacteria.")),
                "Have you tested it yet?", "evidence_testing");
        kit.repo.appendTurn(d.id(), Turn.user("I have to leave now. Can I come back later?"));
        kit.repo.appendTurn(d.id(), Turn.assistant("Of course! Your notes are saved.", List.of()));
        ScriptedLlm llm = new ScriptedLlm();
        var r = kit.interview(llm).handle(kit.repo.get(d.id()), "I'm back. Can we continue where we left off?");
        String text = r.assistantMessage();
        assertEquals(0, llm.calls());
        assertTrue(text.startsWith("Welcome back! So far we've covered invention overview and how it works"), text);
        assertEquals(List.of("evidence_testing"), r.askedFieldIds());
    }

    @Test
    void returningDoesNotRepeatAProcessQuestionWhenWorkDetailsAreStillOpen() {
        Draft d = withNotes(Map.of("summary_general", said("A water bacteria detector."),
                        "technical_description", said("A special material reacts to bacteria."),
                        "previous_public_disclosures", said("An abstract was published last semester.")),
                "Besides anything you've already mentioned, are any papers planned?", "public_disclosures_list");
        ScriptedLlm llm = new ScriptedLlm();
        var r = kit.interview(llm).handle(d, "I'm back. Can we continue where we left off?");
        assertEquals(List.of("difference_from_others"), r.askedFieldIds());
        assertFalse(r.assistantMessage().toLowerCase().contains("funding"));
        assertFalse(r.assistantMessage().toLowerCase().contains("papers"));
    }
}
