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

/**
 * Client for a local OpenAI-compatible chat server (for example {@code mlx_lm.server}).
 * Prompts never leave the machine. Does not rely on JSON-mode support: structured output is enforced by prompts
 * plus Java-side validation and retry.
 */
public final class LocalLlmClient implements LlmClient {
    private final String baseUrl, model;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public LocalLlmClient(String baseUrl, String model, int timeoutSeconds) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    public synchronized String complete(String systemPrompt, String userPrompt, int maxTokens) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model", model, "max_tokens", maxTokens, "temperature", 0, "stream", false,
                    "stop", List.of("<|im_end|>", "<|endoftext|>"),
                    "messages", List.of(Map.of("role", "system", "content", systemPrompt),
                                        Map.of("role", "user", "content", userPrompt))));
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions")).timeout(timeout)
                    .header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new LlmUnavailableException("Local LLM server returned HTTP " + res.statusCode());
            JsonNode text = mapper.readTree(res.body()).path("choices").path(0).path("message").path("content");
            if (!text.isTextual() || text.asText().isBlank()) throw new LlmUnavailableException("Local LLM returned no text");
            return text.asText();
        } catch (java.net.ConnectException e) {
            throw new LlmUnavailableException("Cannot reach the local LLM server at " + baseUrl + ". Is it running?", e);
        } catch (IOException e) {
            throw new LlmUnavailableException("Local LLM request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("Local LLM request interrupted", e);
        }
    }
}
