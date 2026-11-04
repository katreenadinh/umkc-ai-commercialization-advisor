package edu.umkc.advisor.model;

import java.time.Instant;
import java.util.Map;

public record Draft(String id, String ownerToken, String title, Workflow workflow, Map<String, FieldAnswer> answers,
                    String summaryMd, String reviewJson, Instant updatedAt) {
    public Draft {
        answers = answers == null ? Map.of() : Map.copyOf(answers);
    }
    public FieldAnswer answer(String fieldId) { return answers.getOrDefault(fieldId, FieldAnswer.EMPTY); }
}
