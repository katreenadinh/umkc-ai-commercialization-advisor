package edu.umkc.advisor.service;

import edu.umkc.advisor.model.*;
import edu.umkc.advisor.safety.ReferralDecision;
import edu.umkc.advisor.safety.ReferralService;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.*;

/**
 * Builds the preparation notes. Every statement about the invention is either the researcher's own saved text or, when a
 * summary writer is available, a short summary that passed {@link NotesSummaryWriter}'s grounding check and is labelled
 * as AI-written, with the researcher's answers kept underneath.
 */
public final class DraftSummaryBuilder {
    public record SourceRef(String title, String url) {}

    private final ChecklistService checklist;
    private final ReferralService referral;
    private NotesSummaryWriter writer;

    public DraftSummaryBuilder(ChecklistService checklist, ReferralService referral) { this.checklist = checklist; this.referral = referral; }

    @Autowired(required = false)
    public void setSummaryWriter(NotesSummaryWriter writer) { this.writer = writer; }

    public String build(Draft d, List<SourceRef> sources) {
        List<ChecklistService.Section> sections = checklist.sections(d.workflow());
        Map<String, String> summaries = writer == null ? Map.of() : writer.write(d, sections);
        StringBuilder all = new StringBuilder();
        d.answers().values().forEach(a -> all.append(a.text()).append('\n'));
        ReferralDecision rule = referral.evaluate(all.toString());
        boolean disclosureMentioned = rule.triggers().contains(ReferralDecision.Trigger.IMMINENT_DISCLOSURE)
                || rule.triggers().contains(ReferralDecision.Trigger.PAST_DISCLOSURE);

        StringBuilder md = new StringBuilder();
        String titleText = d.answer("title").hasText() ? d.answer("title").text() : d.title();
        md.append("# Invention Disclosure Preparation Notes\n\n");
        md.append("_These are preparation notes, not an official UMKC submission and not a legal determination. ")
          .append("They contain only what the researcher told the coach and have not been sent to the university._\n\n");
        md.append("**AI use:** The notes and section summaries below were organized and written by AI from the conversation. ")
          .append("Please check them for accuracy before sharing.\n\n");
        md.append("- **Working title:** ").append(titleText.isBlank() ? "(none yet)" : titleText.replaceAll("\\s+", " ")).append('\n');
        md.append("- **Type:** ").append(d.workflow() == Workflow.UPDATE_EXISTING
                ? "Update or improvement to an existing disclosure" : "New invention, software, or creation").append('\n');
        md.append("- **Prepared:** ").append(LocalDate.now()).append(" with the UMKC Commercialization Advisor prototype\n\n");

        md.append("## At a glance\n\n");
        Set<String> placed = new HashSet<>();
        Map<String, InformationStatus> statuses = new LinkedHashMap<>();
        for (ChecklistService.Section s : sections) {
            InformationStatus st = InformationStatus.of(s.fields(), d::answer);
            statuses.put(s.section().id(), st);
            md.append("- ").append(s.section().title()).append(": ").append(st.label).append('\n');
            s.fields().forEach(f -> placed.add(f.id()));
        }
        if (disclosureMentioned)
            md.append("- **Public disclosure:** the researcher mentioned past or planned public sharing. Please contact the office before sharing more.\n");
        md.append('\n');

        for (ChecklistService.Section s : sections) {
            ChecklistSection sec = s.section();
            md.append("## ").append(sec.title()).append(" (").append(statuses.get(sec.id()).label).append(")\n\n");
            if (sec.summaryNote() != null && !sec.summaryNote().isBlank()) md.append('_').append(sec.summaryNote()).append("_\n\n");
            String summary = summaries.get(sec.id());
            if (summary != null) md.append("**Summary**\n\n").append(summary).append("\n\n");
            renderNotes(md, s.fields(), d, summary != null);
        }

        List<ChecklistField> other = checklist.applicable(d.workflow()).stream().filter(f -> !placed.contains(f.id())).toList();
        if (!other.isEmpty()) {
            md.append("## Other information\n\n");
            renderNotes(md, other, d, false);
        }

        md.append("## Questions requiring human assistance\n\n");
        md.append("- Whether this work is patentable or novel, who owns it, who qualifies as an inventor, and whether it should be patented or licensed are decided by qualified university staff, not by this tool.\n");
        for (ReferralDecision.Topic t : rule.topics()) md.append("- The researcher asked about ").append(t.label).append(". This needs staff review.\n");
        if (disclosureMentioned)
            md.append("- The researcher mentioned public disclosure (past or planned). Timing can matter, so please tell the commercialization office the details promptly.\n");
        for (ChecklistField f : checklist.applicable(d.workflow()))
            if (f.humanReviewNote() != null && d.answer(f.id()).isAddressed() && d.answer(f.id()).mark() != ResearcherMark.NOT_APPLICABLE)
                md.append("- ").append(f.label()).append(": ").append(f.humanReviewNote()).append('\n');
        md.append('\n');

        md.append("## Suggested next steps\n\n");
        md.append("1. Read and correct these notes. They are only as accurate as what was entered, and any AI summary should be checked against the answers under it.\n");
        md.append("2. Contact the commercialization office: ").append(referral.contact().officeName() == null || referral.contact().officeName().isBlank()
                ? "[CONFIGURE: office name]" : referral.contact().officeName()).append(" at ")
          .append(referral.contact().email() == null || referral.contact().email().isBlank() ? "[CONFIGURE: email]" : referral.contact().email()).append(". Early, informal questions are welcome.\n");
        md.append("3. Complete the university's official Invention Disclosure Form through UMKC's process. These notes are not a submission.\n");
        md.append(d.workflow() == Workflow.UPDATE_EXISTING
                ? "4. Ask staff whether your changes belong in your existing disclosure or should be a separate disclosure.\n"
                : "4. If you are unsure whether this is distinct from something you have already disclosed, ask staff.\n");
        md.append("5. Tell the office about any past or planned public disclosures, with dates.\n");
        return md.toString().stripTrailing() + "\n";
    }

    /** The researcher's answers as a list, then one line naming everything still open. */
    private static void renderNotes(StringBuilder md, List<ChecklistField> fields, Draft d, boolean afterSummary) {
        List<String> open = new ArrayList<>();
        boolean any = false;
        for (ChecklistField f : fields) {
            FieldAnswer a = d.answer(f.id());
            if (!a.hasText() && (a.mark() == ResearcherMark.NONE || a.mark() == ResearcherMark.SKIPPED)) {
                open.add(a.mark() == ResearcherMark.SKIPPED ? f.label() + " (skipped)" : f.label());
                continue;
            }
            if (!any && afterSummary) md.append("**Notes from the conversation**\n\n");
            any = true;
            md.append("- **").append(f.label()).append(":**");
            if (!a.hasText()) {
                md.append(a.mark() == ResearcherMark.NOT_APPLICABLE ? " _Not applicable_" : " _Not yet known_").append('\n');
                continue;
            }
            List<String> lines = a.text().lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
            if (lines.size() == 1) md.append(' ').append(lines.get(0));
            else { md.append('\n'); lines.forEach(l -> md.append("  - ").append(l).append('\n')); md.setLength(md.length() - 1); }
            if (a.mark() == ResearcherMark.NOT_YET_KNOWN) md.append(" _(some of this is not yet known)_");
            md.append('\n');
        }
        if (any) md.append('\n');
        if (!open.isEmpty()) md.append("**Still to discuss:** ").append(String.join("; ", open)).append("\n\n");
    }
}
