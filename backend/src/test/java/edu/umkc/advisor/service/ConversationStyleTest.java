package edu.umkc.advisor.service;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class ConversationStyleTest {

    @Test
    public void stockOpenersAreRemoved() {
        assertEquals("How does it work?", ConversationStyle.polish("Thank you for sharing. How does it work?", List.of()));
        assertEquals("What testing have you done?", ConversationStyle.polish("That's interesting! What testing have you done?", List.of()));
        assertEquals("So how does it detect them?", ConversationStyle.polish("Great question, so how does it detect them?", List.of()));
        assertEquals("Have you built a prototype?", ConversationStyle.polish("Thanks for explaining that. Have you built a prototype?", List.of()));
        assertEquals("Who contributed?", ConversationStyle.polish("Thank you for sharing. That's interesting. Who contributed?", List.of()));
        assertEquals("What problem does this solve?", ConversationStyle.polish("That's noted. What problem does this solve?", List.of()));
        assertEquals("Invention disclosures typically list all contributors.",
                ConversationStyle.polish("That's a good question. Invention disclosures typically list all contributors.", List.of()));
        assertEquals("To explore the potential for real-world use, we can discuss the stage.",
                ConversationStyle.polish("That's a great question! To explore the potential for real-world use, we can discuss the stage.", List.of()));
        assertEquals("What are the results?",
                ConversationStyle.polish("That is a great question. What are the results?", List.of()));
        assertEquals("Could you explain more?",
                ConversationStyle.polish("Good question. Could you explain more?", List.of()));
        assertEquals("What did you find?",
                ConversationStyle.polish("Great question! What did you find?", List.of()));
        assertEquals("Could you describe how the material reacts?",
                ConversationStyle.polish("That's a great start! Could you describe how the material reacts?", List.of()));
        assertEquals("Got it. Could you tell me more about how the device works?",
                ConversationStyle.polish("Got it. That's a great start! Could you tell me more about how the device works?", List.of()));
        assertEquals("Got it. Could you tell me more about the tests?",
                ConversationStyle.polish("Got it. That's great to hear! Could you tell me more about the tests?", List.of()));
        assertEquals("Commercialization is the process of taking research toward use.",
                ConversationStyle.polish("Certainly! Commercialization is the process of taking research toward use.", List.of()));
        assertEquals("What company reached out?",
                ConversationStyle.polish("That's exciting! What company reached out?", List.of()));
        assertEquals("Even at this early stage, your idea can still matter.",
                ConversationStyle.polish("That's great to hear! Even at this early stage, your idea could still be valuable.", List.of()));
    }

    @Test
    public void fillerFramingAtEndOfSentenceIsRemovedSoEchoCanDrop() {
        String user = "I'm developing a small device that can detect harmful bacteria in drinking water.";
        String model = "A device for detecting harmful bacteria in drinking water is a clear starting point. How does it work?";
        String polished = ConversationStyle.polish(model, List.of(), user);
        assertFalse(polished.toLowerCase().contains("starting point"), polished);
        assertTrue(polished.contains("How does it work?"), polished);
    }

    @Test
    public void echoOfUserMessageIsRemoved() {
        String user = "We posted the preprint on arXiv two weeks ago.";
        String model = "So the preprint was posted on arXiv two weeks ago. Have you looked into any existing products?";
        assertEquals("Have you looked into any existing products?", ConversationStyle.polish(model, List.of(), user));
        String user2 = "We published an abstract about the water detection device last semester, but we didn't include all the technical details.";
        String model2 = "Got it. You published an abstract about the water detection device last semester, but it didn't include all the technical details. Roughly when was it shared?";
        String polished = ConversationStyle.polish(model2, List.of(), user2);
        assertFalse(polished.toLowerCase().contains("you published an abstract"), polished);
        assertTrue(polished.contains("Roughly when was it shared?"), polished);
        String restated = ConversationStyle.polish(
                "So, the device uses a special material that reacts in some way when certain bacteria are present. How does that reaction work? What exactly happens when the bacteria are detected?",
                List.of(),
                "It uses a special material that reacts when certain bacteria are present.");
        assertFalse(restated.toLowerCase().contains("so, the device uses"), restated);
        assertEquals(1, restated.chars().filter(c -> c == '?').count(), restated);
    }

    @Test
    public void compoundQuestionsAreSimplifiedToOneQuestion() {
        String compound1 = "What problem does this address, and who runs into it?";
        assertEquals("What problem does this address?", ConversationStyle.polish(compound1, List.of()));

        String compound2 = "Could you tell me a little more about the problem your invention addresses and why it's important?";
        assertEquals("Could you tell me a little more about the problem your invention addresses?", ConversationStyle.polish(compound2, List.of()));

        String compound3 = "Could you explain a bit more about how the sensor works and how it sends the results to the mobile app?";
        assertEquals("Could you explain a bit more about how the sensor works?", ConversationStyle.polish(compound3, List.of()));

        String compound4 = "Could you tell me a bit more about how the device works or what makes it different from existing methods?";
        assertEquals("Could you tell me a bit more about how the device works?", ConversationStyle.polish(compound4, List.of()));
    }

    @Test
    public void redundantStaffDisclaimersAreStripped() {
        String text = "Under UM System regulations (CR&R 100.020), inventions belong to the university. Official determinations are made by university staff. Could you tell me more?";
        assertEquals("Under UM System regulations (CR&R 100.020), inventions belong to the university. Could you tell me more?",
                ConversationStyle.stripRedundantDisclaimer(text));

        String text2 = "Software can be protected. Only patent staff can make that determination for your project. How does it work?";
        assertEquals("Software can be protected. How does it work?",
                ConversationStyle.stripRedundantDisclaimer(text2));
    }

    @Test
    public void naturalOpenersAndContentAreLeftAlone() {
        String s = "Got it. What happens when the conductivity changes?";
        assertEquals(s, ConversationStyle.polish(s, List.of()));
        String t = "Thanks to the grant you mentioned, did a sponsor require any reporting?";
        assertEquals(t, ConversationStyle.polish(t, List.of()));
        assertEquals("Thank you.", ConversationStyle.polish("Thank you.", List.of()));       // nothing left to say: keep, caller treats it as unusable
    }

    @Test
    public void aRepeatedOpenerIsDroppedWhenAQuestionRemains() {
        List<String> prev = List.of("Got it. What does the device do?");
        assertEquals("Who might use it?", ConversationStyle.polish("Got it. Who might use it?", prev));
        assertEquals("Okay. Who might use it?", ConversationStyle.polish("Okay. Who might use it?", prev));          // different opener: kept
        assertEquals("Got it.", ConversationStyle.polish("Got it.", prev));                                          // nothing to keep
    }

    @Test
    public void formLikeMessagesAreRecognised() {
        assertTrue(ConversationStyle.isFormLike("Please tell me:\n1. what it does\n2. who uses it"));
        assertTrue(ConversationStyle.isFormLike("- the title\n- the problem"));
        assertTrue(ConversationStyle.isFormLike("Let's move to the next field: funding."));
        assertTrue(ConversationStyle.isFormLike("Here is the checklist of remaining items."));
        assertTrue(ConversationStyle.isFormLike("x".repeat(1401)));
        assertFalse(ConversationStyle.isFormLike("Got it. What happens when the conductivity changes?"));
    }

    @Test
    public void acknowledgementsRotateAndAreContextual() {
        Set<String> bridges = new HashSet<>(), skips = new HashSet<>();
        for (int i = 0; i < 5; i++) { bridges.add(ConversationStyle.bridge(i)); skips.add(ConversationStyle.skipAck(i)); }
        assertEquals(5, bridges.size());
        assertEquals(3, skips.size());
        assertNotEquals(ConversationStyle.bridge(1), ConversationStyle.bridge(2));
        assertTrue(ConversationStyle.unknownAck("evidence_testing").contains("still document what you've developed"));
        assertTrue(ConversationStyle.unknownAck("difference_from_others").contains("completely fine"));
        assertTrue(ConversationStyle.unknownAck("keywords").contains("Not knowing yet is normal"));
        String expanded = ConversationStyle.expandThinReply("Got it. How does it work?", "technical_description");
        assertTrue(expanded.toLowerCase().contains("how") && expanded.toLowerCase().contains("work"), expanded);
        assertFalse(expanded.toLowerCase().startsWith("got it"), expanded);
        assertTrue(expanded.toLowerCase().contains("for example"), expanded);
        assertTrue(ConversationStyle.genericInterviewQuestion("Got it. Could you tell me more about how the device works?"));
        assertFalse(ConversationStyle.genericInterviewQuestion("What happens when the conductivity changes?"));
        assertFalse(ConversationStyle.genericInterviewQuestion("Could you tell me how the sensor detects contaminants?"));
        assertTrue(ConversationStyle.softQuestion("technical_description").toLowerCase().contains("how it works"));
        assertTrue(ConversationStyle.softQuestion("development_stage").contains("building or testing"));
        assertTrue(ConversationStyle.softQuestion("closest_products").contains("similar"));
        assertNull(ConversationStyle.softQuestion("funding"));
    }

    @Test
    public void promptTeachesTheConversationalStyleAndKeepsItsSafetyAndExtractionRules() throws Exception {
        String p;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("prompts/interview.txt")) {
            p = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(p.contains("ONE follow-up") || p.contains("ONE question"));
        assertTrue(p.contains("Example turns") || p.contains("match this voice"));
        assertTrue(p.contains("We published an abstract last semester, but we didn't include all the technical details."));
        assertTrue(p.contains("I haven't looked into other technologies yet."));
        assertTrue(p.contains("Do NOT begin with \"Thank you for sharing\""));
        assertTrue(p.contains("<avoid_starting_with>"));
        assertTrue(p.contains("Stay with this message"));
        assertTrue(p.contains("That's okay. We can still document what they've developed so far."));
        assertTrue(p.contains("That's completely fine. You don't need to have completed a comparison of existing technologies to discuss your research."));
        assertTrue(p.contains("Do not re-ask something they already answered"));
        assertTrue(p.contains("Explain unfamiliar terms in a few words"));
        assertFalse(p.contains("drinking water"));
        assertFalse(p.contains("electrical conductivity"));
        assertFalse(p.contains("two other graduate students"));
        // preserved behaviour
        assertTrue(p.contains("untrusted data"));
        assertTrue(p.contains("Do NOT add facts, results, numbers, dates, names"));
        assertTrue(p.contains("NEVER say or suggest whether anything is patentable"));
        assertTrue(p.contains("Never suggest the work must be further along"));
        assertTrue(p.contains("\"nextFieldIds\""));
        assertTrue(p.contains("NOT_YET_KNOWN") && p.contains("NOT_APPLICABLE") && p.contains("SKIPPED"));
    }

    public static void main(String[] args) throws Exception {
        ConversationStyleTest t = new ConversationStyleTest();
        t.stockOpenersAreRemoved();
        t.echoOfUserMessageIsRemoved();
        t.compoundQuestionsAreSimplifiedToOneQuestion();
        t.redundantStaffDisclaimersAreStripped();
        t.naturalOpenersAndContentAreLeftAlone();
        t.aRepeatedOpenerIsDroppedWhenAQuestionRemains();
        t.formLikeMessagesAreRecognised();
        t.acknowledgementsRotateAndAreContextual();
        t.promptTeachesTheConversationalStyleAndKeepsItsSafetyAndExtractionRules();
        System.out.println("CONVERSATION STYLE TESTS PASSED!");
    }
}
