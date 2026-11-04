package edu.umkc.advisor.model;

import java.util.List;

public record ChecklistField(
        String id, String section, String label, String description, String whyItHelps, String starterQuestion,
        String origin, String sourceRef, boolean verified, List<String> appliesTo, String humanReviewNote,
        boolean notApplicableAllowed, int priority, int order) {

    public boolean officialFormField() { return "OFFICIAL_FORM".equals(origin); }
    public boolean appliesTo(Workflow w) { return appliesTo == null || appliesTo.isEmpty() || appliesTo.contains(w.name()); }
}
