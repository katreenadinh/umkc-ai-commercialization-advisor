package edu.umkc.advisor.ai;

import edu.umkc.advisor.model.FieldStatus;

import java.util.List;

public record ReviewPayload(List<Entry> fields) {
    public record Entry(String fieldId, FieldStatus status, String currentInformation, String missingDetails,
                        String followUpQuestion) {}
}
