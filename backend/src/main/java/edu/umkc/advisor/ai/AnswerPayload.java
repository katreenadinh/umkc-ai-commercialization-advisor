package edu.umkc.advisor.ai;

import java.util.List;

public record AnswerPayload(String answer, List<String> usedSources, boolean sufficientEvidence, boolean needsStaffFollowUp) {}
