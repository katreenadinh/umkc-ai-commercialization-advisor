package edu.umkc.advisor.ai;

/** Minimal LLM abstraction so services can be unit-tested with a fake. */
public interface LlmClient {
    /** Returns the model's text reply. */
    String complete(String systemPrompt, String userPrompt, int maxTokens);
}
