package edu.umkc.advisor.model;

import java.util.List;
import java.util.function.Function;

/**
 * How much has been collected for one disclosure-note section. COMPLETE means every note in the section is answered or
 * marked not applicable, not that the invention is developed. UNKNOWN means the researcher was asked and does not know
 * yet; it is not progress. A skipped note counts as not discussed.
 */
public enum InformationStatus {
    NOT_DISCUSSED("Not Discussed"),
    PARTIAL("Information Needed"),
    COMPLETE("Completed"),
    UNKNOWN("Not Yet Known");

    public final String label;

    InformationStatus(String label) { this.label = label; }

    /** @param fields the section's notes that apply to this draft's workflow */
    public static InformationStatus of(List<ChecklistField> fields, Function<String, FieldAnswer> answers) {
        List<String> ids = fields.stream().map(ChecklistField::id).toList();
        if (!ids.isEmpty() && ids.stream().allMatch(id -> answered(answers.apply(id)))) return COMPLETE;
        if (ids.stream().anyMatch(id -> answered(answers.apply(id)))) return PARTIAL;
        if (ids.stream().anyMatch(id -> answers.apply(id).mark() == ResearcherMark.NOT_YET_KNOWN)) return UNKNOWN;
        return NOT_DISCUSSED;
    }

    private static boolean answered(FieldAnswer a) { return a.hasText() || a.mark() == ResearcherMark.NOT_APPLICABLE; }
}
