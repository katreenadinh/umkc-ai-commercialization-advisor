package edu.umkc.advisor.service;

import edu.umkc.advisor.model.*;
import edu.umkc.advisor.safety.ReferralDecision;
import edu.umkc.advisor.safety.ReferralService;

import java.time.LocalDate;
import java.util.*;

/**
 * Builds the preparation summary WITHOUT a language model. Every statement about the invention is the researcher's own
 * saved text, so the summary cannot invent technical details, results, dates, contributors, funding or novelty claims.
 */
public final class DraftSummaryBuilder {
    public record SourceRef(String title, String url) {}
    private record Group(String heading, List<String> ids) {}

    private static final List<Group> GROUPS = List.of(
        new Group("Existing disclosure being updated", List.of("existing_disclosure_ref", "what_changed")),
        new Group("Invention title", List.of("title")),
        new Group("Invention overview", List.of("summary_general")),
        new Group("Problem being addressed", List.of("problem_solved")),
        new Group("Technical approach and how it works", List.of("technical_description")),
        new Group("Differences from existing approaches (as described by the researcher)", List.of("difference_from_others", "novel_aspects", "limitations")),
        new Group("Potential applications (possibilities described by the researcher, not established outcomes)", List.of("applications_users")),
        new Group("Testing and supporting evidence", List.of("evidence_testing", "supporting_documents")),
        new Group("Current development stage and key dates", List.of("development_stage", "date_conceived", "date_first_written", "date_first_prototype")),
        new Group("Similar technologies and prior art the researcher knows about", List.of("closest_products", "prior_art")),
        new Group("Inventors and contributors", List.of("potential_inventors")),
        new Group("Funding and sponsorship", List.of("funding")),
        new Group("Collaborations and third-party involvement", List.of("third_party")),
        new Group("Public disclosure history and planned disclosures", List.of("date_first_public_disclosure", "public_disclosures_list", "date_first_offer_to_sell")),
        new Group("Other information", List.of("keywords", "export_control", "potential_licensees", "conflicts_of_interest")));

    private final ChecklistService checklist;
    private final ReferralService referral;

    public DraftSummaryBuilder(ChecklistService checklist, ReferralService referral) { this.checklist = checklist; this.referral = referral; }

    /** @param review the latest review, or null/stale. Only used for the list of unclear items. */
    public String build(Draft d, DisclosureReviewService.ReviewResult review, List<SourceRef> sources) {
        List<ChecklistField> applicable = checklist.applicable(d.workflow());
        Set<String> applicableIds = new HashSet<>();
        applicable.forEach(f -> applicableIds.add(f.id()));
        StringBuilder md = new StringBuilder();
        String titleText = d.answer("title").hasText() ? d.answer("title").text() : d.title();
        md.append("# Invention Disclosure Preparation Summary\n\n");
        md.append("**This is a preparation summary, not an official UMKC submission and not a legal determination.** ")
          .append("It has not been sent to the university. It contains only information you provided; anything you did not ")
          .append("provide is marked as unanswered.\n\n");
        md.append("- Working title: ").append(titleText.isBlank() ? "(none yet)" : titleText.replaceAll("\\s+", " ")).append('\n');
        md.append("- Type: ").append(d.workflow() == Workflow.UPDATE_EXISTING
                ? "Update or improvement to an existing disclosure" : "New invention, software, or creation").append('\n');
        md.append("- Prepared: ").append(LocalDate.now()).append(" (prototype tool)\n\n");

        for (Group g : GROUPS) {
            List<ChecklistField> fs = new ArrayList<>();
            for (String id : g.ids()) if (applicableIds.contains(id)) fs.add(checklist.find(id).orElseThrow());
            if (fs.isEmpty()) continue;
            md.append("## ").append(g.heading()).append("\n\n");
            for (ChecklistField f : fs) renderField(md, f, d.answer(f.id()), fs.size() > 1);
        }

        md.append("## Missing or incomplete information\n\n");
        List<String> gaps = gaps(d, applicable, review);
        if (gaps.isEmpty()) md.append("Nothing is currently marked as missing. Please still read everything above.\n\n");
        else { gaps.forEach(g -> md.append("- ").append(g).append('\n')); md.append('\n'); }

        StringBuilder all = new StringBuilder();
        d.answers().values().forEach(a -> all.append(a.text()).append('\n'));
        ReferralDecision rule = referral.evaluate(all.toString());
        md.append("## Questions requiring human assistance\n\n");
        md.append("- Whether this work is patentable or novel, who owns it, who qualifies as an inventor, and whether it should be patented or licensed are decided by qualified university staff, not by this tool.\n");
        for (ReferralDecision.Topic t : rule.topics()) md.append("- You asked about ").append(t.label).append(". This needs staff review.\n");
        if (rule.triggers().contains(ReferralDecision.Trigger.IMMINENT_DISCLOSURE) || rule.triggers().contains(ReferralDecision.Trigger.PAST_DISCLOSURE))
            md.append("- You mentioned public disclosure (past or planned). Timing can matter, so please tell the commercialization office the details promptly.\n");
        for (ChecklistField f : applicable) if (f.humanReviewNote() != null && d.answer(f.id()).isAddressed() && d.answer(f.id()).mark() != ResearcherMark.NOT_APPLICABLE)
            md.append("- ").append(f.label()).append(": ").append(f.humanReviewNote()).append('\n');
        md.append('\n');

        md.append("## Suggested next steps\n\n");
        md.append("1. Read and correct this summary. It is only as accurate as what you entered.\n");
        md.append("2. Contact the commercialization office: ").append(referral.contact().officeName() == null || referral.contact().officeName().isBlank()
                ? "[CONFIGURE: office name]" : referral.contact().officeName()).append(" at ")
          .append(referral.contact().email() == null || referral.contact().email().isBlank() ? "[CONFIGURE: email]" : referral.contact().email()).append(". Early, informal questions are welcome.\n");
        md.append("3. Complete the university's official Invention Disclosure Form through UMKC's process. This summary is not a submission.\n");
        md.append(d.workflow() == Workflow.UPDATE_EXISTING
                ? "4. Ask staff whether your changes belong in your existing disclosure or should be a separate disclosure.\n"
                : "4. If you are unsure whether this is distinct from something you have already disclosed, ask staff.\n");
        md.append("5. Tell the office about any past or planned public disclosures, with dates.\n\n");

        md.append("## Sources consulted for procedural guidance\n\n");
        md.append("The checklist used to organize this summary follows ").append(checklist.formVersion().isBlank() ? "the official disclosure form" : "Form " + checklist.formVersion()).append(". Items marked as additional detail are not separate fields on the form.\n\n");
        for (SourceRef s : sources) md.append("- ").append(s.title()).append(s.url() == null ? "" : " — " + s.url()).append('\n');
        return md.toString().stripTrailing() + "\n";
    }

    private void renderField(StringBuilder md, ChecklistField f, FieldAnswer a, boolean showLabel) {
        md.append("**").append(f.label()).append("**");
        if (!f.officialFormField() && !"OFFICIAL_PAGE".equals(f.origin()) && !"OFFICIAL_REGULATION".equals(f.origin()))
            md.append(" _(additional detail, not a separate field on the official form)_");
        md.append("\n\n");
        if (a.hasText()) {
            for (String line : a.text().split("\n")) if (!line.isBlank()) md.append(line.strip()).append("\n\n");
            if (a.mark() == ResearcherMark.NOT_YET_KNOWN) md.append("_The researcher indicated that some of this is not yet known._\n\n");
        } else {
            md.append(switch (a.mark()) {
                case NOT_YET_KNOWN -> "_The researcher indicated this is not yet known._";
                case NOT_APPLICABLE -> "_The researcher marked this as not applicable._";
                case SKIPPED -> "_Skipped for now._";
                default -> "_Not yet provided._";
            }).append("\n\n");
        }
    }

    private List<String> gaps(Draft d, List<ChecklistField> applicable, DisclosureReviewService.ReviewResult review) {
        List<String> out = new ArrayList<>();
        Map<String, DisclosureReviewService.FieldReview> byId = new HashMap<>();
        if (review != null) review.fields().forEach(f -> byId.put(f.fieldId(), f));
        for (ChecklistField f : applicable) {
            FieldAnswer a = d.answer(f.id());
            if (a.mark() == ResearcherMark.NOT_APPLICABLE) continue;
            var r = byId.get(f.id());
            if (!a.hasText() && a.mark() != ResearcherMark.NOT_YET_KNOWN) {
                out.add(f.label() + (a.mark() == ResearcherMark.SKIPPED ? " (skipped)" : " (not yet provided)"));
            } else if (a.mark() == ResearcherMark.NOT_YET_KNOWN) {
                out.add(f.label() + " (not yet known)");
            } else if (r != null && (r.status() == FieldStatus.PARTIALLY_PROVIDED || r.status() == FieldStatus.NEEDS_HUMAN_REVIEW)) {
                out.add(f.label() + (r.missingDetails().isBlank() ? "" : ": " + r.missingDetails())
                        + (r.followUpQuestion().isBlank() ? "" : " Possible follow-up: " + r.followUpQuestion()));
            }
        }
        return out;
    }
}
