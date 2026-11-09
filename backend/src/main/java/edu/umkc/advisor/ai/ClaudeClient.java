package edu.umkc.advisor.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umkc.advisor.exception.LlmUnavailableException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Anthropic Messages API client. Never logs prompts, responses, or the API key. */
public final class ClaudeClient implements LlmClient {

    private final String apiKey, model, baseUrl;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public ClaudeClient(String apiKey, String model, String baseUrl, int timeoutSeconds) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, int maxTokens) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmUnavailableException("LLM_API_KEY is not configured");
        }
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model", model,
                    "max_tokens", maxTokens,
                    "temperature", 0,
                    "system", systemPrompt,
                    "messages", List.of(Map.of("role", "user", "content", userPrompt))));
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
                    .timeout(timeout)
                    .header("content-type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();

            int attempts = 0;
            while (true) {
                attempts++;
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                int code = res.statusCode();
                if (code == 200) return extractText(res.body());
                boolean retryable = code == 429 || code >= 500;
                if (!retryable || attempts >= 3) {
                    throw new LlmUnavailableException("LLM API returned HTTP " + code);
                }
                Thread.sleep(500L * attempts * attempts);
            }
        } catch (IOException e) {
            throw new LlmUnavailableException("LLM API request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("LLM API request interrupted", e);
        }
    }

    private String extractText(String json) throws IOException {
        JsonNode content = mapper.readTree(json).path("content");
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) sb.append(block.path("text").asText());
        }
        if (sb.isEmpty()) throw new LlmUnavailableException("LLM API returned no text");
        return sb.toString();
    }
}
