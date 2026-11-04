package edu.umkc.advisor.ai;

import edu.umkc.advisor.model.ResearcherMark;

import java.util.List;

public record InterviewPayload(List<Update> updates, String assistantMessage, List<String> nextFieldIds) {
    public record Update(String fieldId, String text, ResearcherMark mark) {}
}
