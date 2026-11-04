package edu.umkc.advisor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "advisor")
public record AdvisorProperties(Anthropic anthropic, Referral referral, Limits limits, Rag rag, String corsAllowedOrigin, String adminToken) {
    public record Anthropic(String apiKey, String model, String baseUrl, int maxTokens, int timeoutSeconds) {}
    public record Referral(String officeName, String email, String url) {}
    public record Rag(int topK, double minScore) {}
    public record Limits(int maxQuestionChars) {}
}
