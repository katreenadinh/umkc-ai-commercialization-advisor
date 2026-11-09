package edu.umkc.advisor.service;

import edu.umkc.advisor.model.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.*;

import static edu.umkc.advisor.service.TestKit.*;
import static org.junit.jupiter.api.Assertions.*;

class SummaryAndExportTest {
    private final TestKit kit = new TestKit();
    private final DraftSummaryBuilder builder = new DraftSummaryBuilder(kit.checklist, kit.referral);
    private static final List<DraftSummaryBuilder.SourceRef> SRC = List.of(new DraftSummaryBuilder.SourceRef("Official Source A", "https://example.edu/a"));

    private Draft draft(Workflow w, Map<String, FieldAnswer> a) { return kit.withAnswers(kit.newDraft(w), a); }

    private static String section(String md, String heading) {
        int i = md.indexOf("## " + heading);
        int j = md.indexOf("\n## ", i + 1);
        return md.substring(i, j < 0 ? md.length() : j);
    }

    @Test
    void showsTheResearchersAnswersAndListsWhatIsStillOpen() {
        Map<String, FieldAnswer> a = new HashMap<>();
        a.put("summary_general", text("A conductivity-based sensor for drinking water contaminants."));
        a.put("technical_description", text("It measures changes in electrical conductivity across a graphene film."));
        a.put("evidence_testing", new FieldAnswer("", ResearcherMark.NOT_YET_KNOWN));
        a.put("funding", new FieldAnswer("", ResearcherMark.NOT_APPLICABLE));
        a.put("keywords", new FieldAnswer("", ResearcherMark.SKIPPED));
        String md = builder.build(draft(Workflow.NEW_INVENTION, a), SRC);
        assertTrue(md.contains("- **General description:** A conductivity-based sensor for drinking water contaminants."));
        assertTrue(md.contains("- **Technical description:** It measures changes in electrical conductivity across a graphene film."));
        assertTrue(md.contains("- **Testing conducted:** _Not yet known_"));
        assertTrue(md.contains("- **University funding or research grants:** _Not applicable_"));
        assertTrue(section(md, "Invention Overview").contains("**Still to discuss:** Title or working title; Main purpose; Keywords (skipped); Underlying research or scientific finding"));
        assertTrue(md.contains("not an official UMKC submission and not a legal determination"));
        assertFalse(md.contains("Sources consulted"));
        assertFalse(md.contains("Official Source A"));
        assertTrue(md.contains("possibilities described by the researcher, not established outcomes"));
        assertFalse(md.contains("Not yet provided"));
        assertFalse(md.contains("## Missing or incomplete information"));
    }

    @Test
    void anEmptyDraftHasNoResearcherContentAndEveryNoteIsListedOnceAsStillToDiscuss() {
        String md = builder.build(draft(Workflow.NEW_INVENTION, Map.of()), SRC);
        assertFalse(md.contains("- **General description:**"));
        assertFalse(md.contains("**Summary**"));
        int first = md.toLowerCase().indexOf("patentable");
        assertEquals(first, md.toLowerCase().lastIndexOf("patentable"));
        assertTrue(md.indexOf("## Questions requiring human assistance") < first);
        for (Workflow w : Workflow.values()) {
            String m = builder.build(draft(w, Map.of()), SRC);
            String open = String.join("\n", m.lines().filter(l -> l.startsWith("**Still to discuss:**")).toList());
            for (ChecklistField f : kit.checklist.applicable(w)) assertTrue(open.contains(f.label()), w + " / " + f.id());
        }
    }

    @Test
    void notesAreGroupedIntoTheNineSectionsWithTheirStatus() {
        Map<String, FieldAnswer> a = Map.of("problem_solved", text("Field tests take days."), "problem_who", text("Rural utilities."),
                "problem_challenges", text("Labs are far away."), "problem_importance", text("Lead harms children."),
                "previous_public_disclosures", new FieldAnswer("", ResearcherMark.NOT_YET_KNOWN));
        String md = builder.build(draft(Workflow.NEW_INVENTION, a), SRC);
        List<String> titles = List.of("## At a glance", "## Invention Overview (Not Discussed)", "## Problem (Completed)", "## How It Works",
                "## Differentiation", "## Public Disclosure (Not Yet Known)", "## Development and Evidence", "## Applications", "## Inventors",
                "## Funding and Outside Support", "## Other information", "## Questions requiring human assistance");
        int last = -1;
        for (String t : titles) { int at = md.indexOf(t); assertTrue(at > last, t); last = at; }
        String glance = section(md, "At a glance");
        assertTrue(glance.contains("- Problem: Completed"));
        assertTrue(glance.contains("- Inventors: Not Discussed"));
        String other = section(md, "Other information");
        assertTrue(other.contains("Export control or controlled information"));
        assertTrue(other.contains("Possible conflicts of interest with commercial contacts"));
    }

    @Test
    void multiLineAnswersBecomeSubBullets() {
        String md = builder.build(draft(Workflow.NEW_INVENTION, Map.of("potential_inventors", text("Ana ran the experiments.\nBo wrote the control software."))), SRC);
        assertTrue(md.contains("- **Names of potential inventors and contributors:**\n  - Ana ran the experiments.\n  - Bo wrote the control software.\n"));
    }

    @Test
    void groundedAiSummaryIsShownAboveTheAnswersAndLabelled() {
        var llm = new ScriptedLlm("{\"sections\":[{\"id\":\"problem\",\"summary\":\"The researcher says field tests for rural utilities take days.\"},"
                + "{\"id\":\"overview\",\"summary\":\"A patented sensor by Acme Corp.\"}]}");
        DraftSummaryBuilder b = new DraftSummaryBuilder(kit.checklist, kit.referral);
        b.setSummaryWriter(new NotesSummaryWriter(llm, "system", 500));
        String md = b.build(draft(Workflow.NEW_INVENTION, Map.of("problem_solved", text("Field tests take days."),
                "problem_who", text("Rural utilities."), "summary_general", text("A water sensor."))), SRC);
        String problem = section(md, "Problem");
        assertTrue(md.indexOf("**AI use:**") < md.indexOf("## At a glance"));
        assertFalse(problem.contains("written by AI"));
        assertTrue(problem.indexOf("**Summary**") < problem.indexOf("The researcher says field tests"));
        assertTrue(problem.indexOf("The researcher says field tests") < problem.indexOf("**Notes from the conversation**"));
        assertTrue(problem.contains("- **Problem or need being addressed:** Field tests take days."));
        assertFalse(md.contains("Acme"), "summaries that add names or judgements are dropped");
        assertTrue(section(md, "Invention Overview").contains("- **General description:** A water sensor."));
    }

    @Test
    void notesStillBuildWhenTheModelIsDown() {
        var down = new edu.umkc.advisor.ai.LlmClient() { public String complete(String s, String u, int m) { throw new edu.umkc.advisor.exception.LlmUnavailableException("down"); } };
        DraftSummaryBuilder b = new DraftSummaryBuilder(kit.checklist, kit.referral);
        b.setSummaryWriter(new NotesSummaryWriter(down, "system", 500));
        String md = b.build(draft(Workflow.NEW_INVENTION, Map.of("summary_general", text("A water sensor."))), SRC);
        assertTrue(md.contains("- **General description:** A water sensor."));
        assertFalse(md.contains("**Summary**"));
    }

    @Test
    void updateWorkflowIsDistinguishedFromNewInvention() {
        String upd = builder.build(draft(Workflow.UPDATE_EXISTING, Map.of("what_changed", text("We added a second electrode."))), SRC);
        String neu = builder.build(draft(Workflow.NEW_INVENTION, Map.of()), SRC);
        assertTrue(upd.contains("Update or improvement to an existing disclosure"));
        assertTrue(upd.contains("Existing disclosure being updated"));
        assertTrue(upd.contains("We added a second electrode."));
        assertTrue(upd.contains("whether your changes belong in your existing disclosure or should be a separate disclosure"));
        assertTrue(neu.contains("New invention, software, or creation"));
        assertFalse(neu.contains("Existing disclosure being updated"));
    }

    @Test
    void publicDisclosureAndHumanReviewItemsAreSurfaced() {
        Map<String, FieldAnswer> a = Map.of("public_disclosures_list", text("I'm presenting a poster next week."),
                "potential_inventors", text("Ana and Bo."));
        String md = builder.build(draft(Workflow.NEW_INVENTION, a), SRC);
        String help = section(md, "Questions requiring human assistance");
        assertTrue(help.contains("public disclosure"));
        assertTrue(help.contains("legal determination"));
        assertTrue(help.contains("decided by qualified university staff"));
        assertTrue(section(md, "At a glance").contains("**Public disclosure:**"));
        assertTrue(md.contains("office@example.edu"));
    }

    @Test
    void regeneratedSummaryReflectsLatestEditsAndDropsOldText() {
        Draft d = draft(Workflow.NEW_INVENTION, Map.of("summary_general", text("Old description.")));
        assertTrue(builder.build(d, SRC).contains("Old description."));
        Draft d2 = kit.withAnswers(d, Map.of("summary_general", text("New description after edit.")));
        String md = builder.build(d2, SRC);
        assertTrue(md.contains("New description after edit."));
        assertFalse(md.contains("Old description."));
    }

    @Test
    void docxExportIsAWellFormedWordPackageWithTheResearchersText() throws Exception {
        Draft d = draft(Workflow.NEW_INVENTION, Map.of("summary_general", text("A sensor for <water> & \"contaminants\"."),
                "potential_inventors", text("Ana ran the experiments.\nBo wrote the control software.")));
        String md = builder.build(d, SRC);
        byte[] bytes = new DocxExporter().export(md);

        Map<String, byte[]> parts = new HashMap<>();
        try (var zin = new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var e = zin.getNextEntry(); e != null; e = zin.getNextEntry()) parts.put(e.getName(), zin.readAllBytes());
        }
        assertTrue(parts.keySet().containsAll(List.of("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/_rels/document.xml.rels")));

        var dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        for (byte[] xml : parts.values()) dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml));   // every part is well-formed XML
        var doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(parts.get("word/document.xml")));
        String ns = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        var paras = doc.getElementsByTagNameNS(ns, "p");
        List<String> lines = new ArrayList<>();
        int headings = 0;
        for (int i = 0; i < paras.getLength(); i++) {
            var p = (org.w3c.dom.Element) paras.item(i);
            StringBuilder sb = new StringBuilder();
            var ts = p.getElementsByTagNameNS(ns, "t");
            for (int j = 0; j < ts.getLength(); j++) sb.append(ts.item(j).getTextContent());
            lines.add(sb.toString());
            if (p.getElementsByTagNameNS(ns, "pStyle").getLength() > 0) headings++;
        }
        assertTrue(lines.contains("Invention Disclosure Preparation Notes"));
        assertTrue(lines.stream().anyMatch(l -> l.endsWith("A sensor for <water> & \"contaminants\".")));   // special characters survive escaping
        assertTrue(lines.contains("\u2013 Ana ran the experiments."));
        assertTrue(lines.contains("\u2013 Bo wrote the control software."));
        assertTrue(lines.stream().anyMatch(l -> l.contains("not an official UMKC submission")));
        assertTrue(headings >= 10);
        assertTrue(lines.stream().noneMatch(l -> l.contains("**")));                      // markdown markers became formatting
        assertEquals("a b", DocxExporter.escape("a\u0000 b").replace("  ", " "));          // illegal control characters dropped
    }
}
