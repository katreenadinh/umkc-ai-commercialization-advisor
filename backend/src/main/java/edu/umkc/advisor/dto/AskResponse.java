package edu.umkc.advisor.dto;

import java.util.List;

public record AskResponse(
        AnswerType type,
        String message,
        List<Citation> citations,
        Referral referral,
        Relevance relevance,
        boolean insufficientEvidence,
        String disclaimer) {

    public enum AnswerType { ANSWER, CLARIFICATION, REFERRAL, OUT_OF_SCOPE, BLOCKED }

    public record Referral(boolean required, String trigger, String note) {}

    public record Relevance(String category, String nextAction, String reason) {}

    public static final String DISCLAIMER =
            "General educational information only. Not legal advice and not an official university determination.";
}
