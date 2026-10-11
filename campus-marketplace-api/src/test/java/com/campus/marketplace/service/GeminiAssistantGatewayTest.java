package com.campus.marketplace.service;

import com.campus.marketplace.config.AssistantProperties;
import com.campus.marketplace.dto.AssistantRequest.Mode;
import com.campus.marketplace.exception.AssistantUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Against a local HTTP stub: the request Gemini receives, the parsing of its answer, and every failure mapping. */
class GeminiAssistantGatewayTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicReference<Long> delayMs = new AtomicReference<>(0L);
    private final AtomicReference<String> seenPath = new AtomicReference<>();
    private final AtomicReference<String> seenKey = new AtomicReference<>();
    private final AtomicReference<JsonNode> seenBody = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
    /** When set, the first request gets this status and later ones get {@link #status}. */
    private final AtomicReference<Integer> firstStatus = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenPath.set(exchange.getRequestURI().toString());
            seenKey.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            seenBody.set(JSON.readTree(exchange.getRequestBody()));
            try {
                Thread.sleep(delayMs.get());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            int n = requests.incrementAndGet();
            int code = n == 1 && firstStatus.get() != null ? firstStatus.get() : status.get();
            byte[] out = (code == 200 ? responseBody.get() : "{\"error\":{\"code\":" + code + "}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GeminiAssistantGateway gateway(boolean search) {
        AssistantProperties props = new AssistantProperties(true, AssistantProperties.Gateway.GEMINI, "test-key-123",
                "gemini-test", "http://127.0.0.1:" + server.getAddress().getPort(), search, false, "low",
                0, 0, Duration.ofMillis(800), 4_000_000, new BigDecimal("120"), List.of());
        return new GeminiAssistantGateway(props, RestClient.builder());
    }

    private static AssistantGateway.Prompt prompt(boolean search) {
        return new AssistantGateway.Prompt(Mode.ASK, "rules",
                List.of(new AssistantGateway.Turn("user", "data"), new AssistantGateway.Turn("model", "ok"),
                        new AssistantGateway.Turn("user", "question")),
                new AssistantGateway.Image("image/jpeg", new byte[]{1, 2, 3}), search);
    }

    @Test
    void sendsTheKeyInAHeader_theImageInline_andNoToolsWithoutSearch() {
        responseBody.set("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hello\"}]}}]}");
        AssistantGateway.Reply reply = gateway(false).generate(prompt(false));

        assertThat(reply.text()).isEqualTo("hello");
        assertThat(seenPath.get()).isEqualTo("/v1beta/models/gemini-test:generateContent").doesNotContain("key=");
        assertThat(seenKey.get()).isEqualTo("test-key-123");
        JsonNode body = seenBody.get();
        assertThat(body.path("systemInstruction").path("parts").path(0).path("text").asText()).isEqualTo("rules");
        assertThat(body.path("contents")).hasSize(3);
        JsonNode first = body.path("contents").path(0).path("parts");
        assertThat(first.path(0).path("inline_data").path("mime_type").asText()).isEqualTo("image/jpeg");
        assertThat(first.path(0).path("inline_data").path("data").asText()).isEqualTo("AQID");
        assertThat(first.path(1).path("text").asText()).isEqualTo("data");
        assertThat(body.path("contents").path(1).path("role").asText()).isEqualTo("model");
        assertThat(body.has("tools")).isFalse();
        assertThat(body.path("generationConfig").path("thinkingConfig").path("thinkingLevel").asText()).isEqualTo("low");
    }

    @Test
    void withSearch_sendsTheGoogleSearchTool_andReadsGroundingSources() {
        responseBody.set("""
                {"candidates":[{"content":{"parts":[{"text":"thinking...","thought":true},{"text":"Answer "},{"text":"here"}]},
                  "groundingMetadata":{"groundingChunks":[
                    {"web":{"uri":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/a","title":"bikroy.com"}},
                    {"web":{"uri":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/a","title":"bikroy.com"}},
                    {"web":{"uri":"javascript:alert(1)","title":"evil"}},
                    {"web":{"uri":"https://www.startech.com.bd/x","title":""}}]}}]}""");
        AssistantGateway.Reply reply = gateway(true).generate(prompt(true));

        assertThat(seenBody.get().path("tools").path(0).has("google_search")).isTrue();
        assertThat(reply.text()).isEqualTo("Answer here");
        assertThat(reply.sources()).extracting(AssistantGateway.Source::title).containsExactly("bikroy.com", "www.startech.com.bd");
    }

    @Test
    void blockedOrEmptyAnswer_isEmptyText() {
        responseBody.set("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");
        assertThat(gateway(false).generate(prompt(false)).text()).isEmpty();
    }

    @Test
    void quotaUsedUp_429_isUnavailable_withoutRetrying() {
        status.set(429);
        assertThatThrownBy(() -> gateway(false).generate(prompt(false))).isInstanceOf(AssistantUnavailableException.class);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void serverError_5xx_isRetriedOnce_thenUnavailable() {
        status.set(503);
        assertThatThrownBy(() -> gateway(false).generate(prompt(false))).isInstanceOf(AssistantUnavailableException.class);
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void aBriefOverload_isHiddenByTheRetry() {
        firstStatus.set(503);
        responseBody.set("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}");
        assertThat(gateway(false).generate(prompt(false)).text()).isEqualTo("ok");
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void aSlow5xx_isNotRetried_soTheTotalStaysUnderTheBrowserTimeout() {
        status.set(503);
        delayMs.set(300L);
        GeminiAssistantGateway gateway = gateway(false);
        gateway.setRetryOnlyWithin(java.time.Duration.ofMillis(100));
        assertThatThrownBy(() -> gateway.generate(prompt(false))).isInstanceOf(AssistantUnavailableException.class);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void badKey_403_isUnavailable_withoutRetrying() {
        status.set(403);
        assertThatThrownBy(() -> gateway(false).generate(prompt(false))).isInstanceOf(AssistantUnavailableException.class);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void timeout_isUnavailable() {
        delayMs.set(2_000L);
        assertThatThrownBy(() -> gateway(false).generate(prompt(false))).isInstanceOf(AssistantUnavailableException.class);
    }

    @Test
    void garbageBody_isUnavailable() {
        responseBody.set("<html>not json</html>");
        assertThatThrownBy(() -> gateway(false).generate(prompt(false))).isInstanceOf(AssistantUnavailableException.class);
    }

    @Test
    void propertiesNeverPrintTheKey() {
        AssistantProperties props = new AssistantProperties(true, AssistantProperties.Gateway.GEMINI, "secret-key-value",
                "m", "http://x", false, false, null, 0, 0, Duration.ofSeconds(1), 1, BigDecimal.TEN, null);
        assertThat(props.toString()).doesNotContain("secret-key-value").contains("(set)");
    }
}
