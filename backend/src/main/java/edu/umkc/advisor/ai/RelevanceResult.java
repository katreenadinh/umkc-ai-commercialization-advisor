package edu.umkc.advisor.ai;

import edu.umkc.advisor.model.NextAction;
import edu.umkc.advisor.model.RelevanceCategory;

public record RelevanceResult(
        RelevanceCategory category,
        NextAction nextAction,
        String reason,
        boolean humanReferralNeeded,
        String clarifyingQuestion,
        double confidence) {}
