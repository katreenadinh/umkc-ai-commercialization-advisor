package edu.umkc.advisor.service;

import edu.umkc.advisor.ingestion.Segment;
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

    @Test
    void preservesResearcherWordingAndMarksEverythingElseUnanswered() {
        Map<String, FieldAnswer> a = new HashMap<>();
        a.put("summary_general", text("A conductivity-based sensor for drinking water contaminants."));
        a.put("technical_description", text("It measures changes in electrical conductivity across a graphene film."));
        a.put("evidence_testing", new FieldAnswer("", ResearcherMark.NOT_YET_KNOWN));
        a.put("funding", new FieldAnswer("", ResearcherMark.NOT_APPLICABLE));
        a.put("keywords", new FieldAnswer("", ResearcherMark.SKIPPED));
        String md = builder.build(draft(Workflow.NEW_INVENTION, a), null, SRC);
        assertTrue(md.contains("A conductivity-based sensor for drinking water contaminants."));
        assertTrue(md.contains("It measures changes in electrical conductivity across a graphene film."));
        assertTrue(md.contains("_The researcher indicated this is not yet known._"));
        assertTrue(md.contains("_The researcher marked this as not applicable._"));
        assertTrue(md.contains("_Skipped for now._"));
        assertTrue(md.contains("_Not yet provided._"));
        assertTrue(md.contains("not an official UMKC submission and not a legal determination"));
        assertTrue(md.contains("Official Source A — https://example.edu/a"));
        assertTrue(md.contains("possibilities described by the researcher, not established outcomes"));
    }

    @Test
    void containsNoFactsBeyondWhatTheResearcherSaid() {
        String md = builder.build(draft(Workflow.NEW_INVENTION, Map.of()), null, SRC);
        // An empty draft must contain no researcher-specific content: every field is "not yet provided".
        long fields = kit.checklist.applicable(Workflow.NEW_INVENTION).size();
        assertEquals(fields, md.split("_Not yet provided\\._", -1).length - 1);
        // the only mention of patentability is the standing "staff decide this" statement
        int first = md.toLowerCase().indexOf("patentable");
        assertEquals(first, md.toLowerCase().lastIndexOf("patentable"));
        assertTrue(md.indexOf("## Questions requiring human assistance") < first);
    }

    @Test
    void everyApplicableFieldAppearsExactlyOnceInBothWorkflows() {
        for (Workflow w : Workflow.values()) {
            String md = builder.build(draft(w, Map.of()), null, SRC);
            for (ChecklistField f : kit.checklist.applicable(w)) {
                int count = md.split("\\*\\*" + java.util.regex.Pattern.quote(f.label()) + "\\*\\*", -1).length - 1;
                assertEquals(1, count, w + " / " + f.id());
            }
        }
    }

    @Test
    void updateWorkflowIsDistinguishedFromNewInvention() {
        String upd = builder.build(draft(Workflow.UPDATE_EXISTING, Map.of("what_changed", text("We added a second electrode."))), null, SRC);
        String neu = builder.build(draft(Workflow.NEW_INVENTION, Map.of()), null, SRC);
        assertTrue(upd.contains("Update or improvement to an existing disclosure"));
        assertTrue(upd.contains("Existing disclosure being updated"));
        assertTrue(upd.contains("We added a second electrode."));
        assertTrue(upd.contains("whether your changes belong in your existing disclosure or should be a separate disclosure"));
        assertTrue(neu.contains("New invention, software, or creation"));
        assertFalse(neu.contains("Existing disclosure being updated"));
    }

    @Test
    void additionalDetailIsLabelledAsNotPartOfTheOfficialForm() {
        String md = builder.build(draft(Workflow.NEW_INVENTION, Map.of("evidence_testing", text("Lab test only."))), null, SRC);
        int i = md.indexOf("**Evidence, experiments and testing**");
        assertTrue(md.substring(i, i + 160).contains("not a separate field on the official form"));
        int j = md.indexOf("**General description**");
        assertFalse(md.substring(j, j + 120).contains("not a separate field"));
    }

    @Test
    void publicDisclosureAndHumanReviewItemsAreSurfaced() {
        Map<String, FieldAnswer> a = Map.of("public_disclosures_list", text("I'm presenting a poster next week."),
                "potential_inventors", text("Ana and Bo."));
        String md = builder.build(draft(Workflow.NEW_INVENTION, a), null, SRC);
        int i = md.indexOf("## Questions requiring human assistance");
        String section = md.substring(i, md.indexOf("## Suggested next steps"));
        assertTrue(section.contains("public disclosure"));
        assertTrue(section.contains("legal determination"));
        assertTrue(section.contains("decided by qualified university staff"));
        assertTrue(md.contains("office@example.edu"));
    }

    @Test
    void regeneratedSummaryReflectsLatestEditsAndDropsOldText() {
        Draft d = draft(Workflow.NEW_INVENTION, Map.of("summary_general", text("Old description.")));
        assertTrue(builder.build(d, null, SRC).contains("Old description."));
        Draft d2 = kit.withAnswers(d, Map.of("summary_general", text("New description after edit.")));
        String md = builder.build(d2, null, SRC);
        assertTrue(md.contains("New description after edit."));
        assertFalse(md.contains("Old description."));
    }

    @Test
    void docxExportIsAWellFormedWordPackageWithTheResearchersText() throws Exception {
        Draft d = draft(Workflow.NEW_INVENTION, Map.of("summary_general", text("A sensor for <water> & \"contaminants\"."),
                "potential_inventors", text("Ana ran the experiments.\nBo wrote the control software.")));
        String md = builder.build(d, null, SRC);
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
        assertTrue(lines.contains("Invention Disclosure Preparation Summary"));
        assertTrue(lines.contains("A sensor for <water> & \"contaminants\"."));          // special characters survive escaping
        assertTrue(lines.contains("Ana ran the experiments."));
        assertTrue(lines.contains("Bo wrote the control software."));
        assertTrue(lines.stream().anyMatch(l -> l.contains("not an official UMKC submission")));
        assertTrue(headings >= 10);
        assertTrue(lines.stream().noneMatch(l -> l.contains("**")));                      // markdown markers became formatting
        assertEquals("a b", DocxExporter.escape("a\u0000 b").replace("  ", " "));          // illegal control characters dropped
    }
}
