package com.campus.marketplace.service;

import com.campus.marketplace.config.AssistantProperties;
import com.campus.marketplace.exception.AssistantUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gemini {@code generateContent}, with the {@code google_search} tool (grounding) when web search is on. The key goes in the
 * {@code x-goog-api-key} header, never in the URL, so it can't end up in an access log. Nothing from the request or
 * the answer is logged here: only the HTTP status when a call fails.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.assistant.gateway", havingValue = "gemini", matchIfMissing = true)
public class GeminiAssistantGateway implements AssistantGateway {

    static final int MAX_SOURCES = 8;

    private final AssistantProperties properties;
    private final RestClient restClient;

    public GeminiAssistantGateway(AssistantProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.timeout());
        this.restClient = builder.clone().baseUrl(properties.baseUrl()).requestFactory(factory).build();
        if (properties.enabled() && !properties.hasApiKey()) {
            log.warn("ASSISTANT_ENABLED is true but GEMINI_API_KEY is not set; the assistant stays off");
        }
    }

    @Override
    public Reply generate(Prompt prompt) {
        if (!properties.hasApiKey()) {
            throw AssistantUnavailableException.off();
        }
        JsonNode body;
        try {
            body = restClient.post()
                    .uri("/v1beta/models/{model}:generateContent", properties.model())
                    .header("x-goog-api-key", properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(prompt))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            log.warn("Gemini call failed: HTTP {}", status.value());
            // 429 (quota used up), 503 (overloaded) and anything else read the same to the student.
            throw AssistantUnavailableException.busy();
        } catch (ResourceAccessException e) {
            log.warn("Gemini call failed: timeout or connection error");
            throw AssistantUnavailableException.busy();
        } catch (RestClientException e) {
            log.warn("Gemini call failed: unreadable response");
            throw AssistantUnavailableException.busy();
        }
        return parse(body);
    }

    Map<String, Object> requestBody(Prompt prompt) {
        List<Map<String, Object>> contents = new ArrayList<>();
        for (int i = 0; i < prompt.turns().size(); i++) {
            Turn turn = prompt.turns().get(i);
            List<Map<String, Object>> parts = new ArrayList<>();
            if (i == 0 && prompt.image() != null) {
                parts.add(Map.of("inline_data", Map.of(
                        "mime_type", prompt.image().mimeType(),
                        "data", Base64.getEncoder().encodeToString(prompt.image().data()))));
            }
            parts.add(Map.of("text", turn.text()));
            contents.add(Map.of("role", turn.role(), "parts", parts));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", prompt.systemInstruction()))));
        body.put("contents", contents);
        if (prompt.webSearch()) {
            body.put("tools", List.of(Map.of("google_search", Map.of())));
        }
        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("temperature", 0.3);
        // Includes the thinking tokens; an answer is ~300 words.
        generation.put("maxOutputTokens", 4096);
        if (properties.thinkingLevel() != null && !properties.thinkingLevel().isBlank()) {
            generation.put("thinkingConfig", Map.of("thinkingLevel", properties.thinkingLevel().strip()));
        }
        body.put("generationConfig", generation);
        return body;
    }

    /** Joins the answer's text parts (skipping "thought" parts) and collects the grounding sources. */
    static Reply parse(JsonNode body) {
        if (body == null) {
            return new Reply("", List.of());
        }
        JsonNode candidate = body.path("candidates").path(0);
        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (!part.path("thought").asBoolean(false) && part.hasNonNull("text")) {
                text.append(part.get("text").asText());
            }
        }
        List<Source> sources = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode chunk : candidate.path("groundingMetadata").path("groundingChunks")) {
            String url = chunk.path("web").path("uri").asText("");
            if (!isWebUrl(url) || !seen.add(url) || sources.size() >= MAX_SOURCES) {
                continue;
            }
            String title = chunk.path("web").path("title").asText("");
            sources.add(new Source(title.isBlank() ? URI.create(url).getHost() : title, url));
        }
        return new Reply(text.toString().strip(), List.copyOf(sources));
    }

    /** Only http(s) links reach the page: a "javascript:" source would be a link the student could click. */
    static boolean isWebUrl(String url) {
        try {
            URI uri = URI.create(url);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
