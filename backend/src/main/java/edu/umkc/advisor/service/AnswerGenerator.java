package edu.umkc.advisor.service;

import edu.umkc.advisor.dto.Citation;

import java.util.List;

/** Produces a grounded answer. The RAG implementation is RagAdvisorService (stage 4). */
public interface AnswerGenerator {
    record GeneratedAnswer(String text, List<Citation> citations, boolean insufficientEvidence) {}

    GeneratedAnswer generate(String question);
}
