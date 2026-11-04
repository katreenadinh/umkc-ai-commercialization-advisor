package edu.umkc.advisor.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import edu.umkc.advisor.exception.LlmUnavailableException;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LocalLlmClientTest {
    private static HttpServer server(int status, String body, AtomicReference<String> path, AtomicReference<String> req) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            path.set(ex.getRequestURI().getPath());
            req.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.start();
        return s;
    }

    @Test
    void callsOpenAiCompatibleChatEndpointOnLocalhost() throws Exception {
        AtomicReference<String> path = new AtomicReference<>(), req = new AtomicReference<>();
        HttpServer s = server(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"ok\\\":true}\"}}]}", path, req);
        try {
            var client = new LocalLlmClient("http://127.0.0.1:" + s.getAddress().getPort() + "/v1/", "local-model", 10);
            assertEquals("{\"ok\":true}", client.complete("SYSTEM TEXT", "USER TEXT", 123));
            assertEquals("/v1/chat/completions", path.get());
            JsonNode body = new ObjectMapper().readTree(req.get());
            assertEquals("local-model", body.get("model").asText());
            assertEquals(0, body.get("temperature").asInt());
            assertEquals(123, body.get("max_tokens").asInt());
            assertFalse(body.get("stream").asBoolean());
            assertEquals("system", body.get("messages").get(0).get("role").asText());
            assertEquals("USER TEXT", body.get("messages").get(1).get("content").asText());
        } finally { s.stop(0); }
    }

    @Test
    void serverErrorsAndEmptyReplies() throws Exception {
        AtomicReference<String> p = new AtomicReference<>(), r = new AtomicReference<>();
        HttpServer err = server(500, "boom", p, r);
        HttpServer empty = server(200, "{\"choices\":[{\"message\":{\"content\":\"\"}}]}", p, r);
        try {
            assertThrows(LlmUnavailableException.class, () -> new LocalLlmClient("http://127.0.0.1:" + err.getAddress().getPort() + "/v1", "m", 5).complete("s", "u", 10));
            assertThrows(LlmUnavailableException.class, () -> new LocalLlmClient("http://127.0.0.1:" + empty.getAddress().getPort() + "/v1", "m", 5).complete("s", "u", 10));
        } finally { err.stop(0); empty.stop(0); }
    }

    @Test
    void unreachableServerGivesAHelpfulMessage() {
        var e = assertThrows(LlmUnavailableException.class, () -> new LocalLlmClient("http://127.0.0.1:1/v1", "m", 3).complete("s", "u", 10));
        assertTrue(e.getMessage().contains("Is it running"));
    }
}
