package edu.umkc.advisor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "advisor")
public record AdvisorProperties(Llm llm, Referral referral, Limits limits, Rag rag, String corsAllowedOrigin) {
    /** provider: "local" (OpenAI-compatible server such as mlx_lm.server) or "anthropic" (optional). */
    public record Llm(String provider, int maxTokens, int timeoutSeconds, Local local, Anthropic anthropic) {}
    public record Local(String baseUrl, String model) {}
    public record Anthropic(String apiKey, String model, String baseUrl) {}
    public record Referral(String officeName, String email, String url) {}
    public record Limits(int maxQuestionChars, int maxMessageChars, int maxDraftsPerOwner) {}
    public record Rag(int topK, double minScore) {}
}
