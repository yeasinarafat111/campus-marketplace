package com.campus.marketplace.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Bound from {@code app.assistant.*}: the "Ask AI" shopping assistant on the listing page.
 *
 * @param enabled           master switch (ASSISTANT_ENABLED, default false). Off, the endpoint answers 503 and
 *                          {@code GET /api/config} tells the frontend to hide the button
 * @param gateway           {@code gemini} (the real API) or {@code fake} (canned answers, for tests and local demos)
 * @param apiKey            GEMINI_API_KEY, mounted from Secret Manager. Never logged. Without it the Gemini gateway counts
 *                          as not configured and the assistant is off
 * @param model             ASSISTANT_MODEL, e.g. gemini-3.8-flash. Must accept image input
 * @param baseUrl           the Gemini API root (overridable for tests)
 * @param searchEnabled     ASSISTANT_SEARCH_ENABLED: send the google_search tool (grounding). Off by default because the
 *                          free tier has no grounding quota for current models; answers then come from model knowledge
 * @param verdictEnabled    ASSISTANT_VERDICT_ENABLED: the "Is it a good buy?" price check. Needs real prices, so it
 *                          only makes sense with a search source; off by default
 * @param thinkingLevel     Gemini 3 thinking level ("low" keeps answers around 5 s); blank leaves the model default
 * @param perUserPerHour    calls one user may make in a sliding hour; 0 = unlimited (the default)
 * @param globalPerDay      calls the whole service may make in a sliding 24 hours; 0 = unlimited (the default)
 * @param timeout           how long one Gemini call may take
 * @param maxImageBytes     the largest listing photo sent along (bigger ones are skipped and the call goes text-only)
 * @param bdtPerUsd         ASSISTANT_BDT_PER_USD: the rate the price check converts Bangladeshi prices with
 * @param bangladeshDomains sites counted as Bangladeshi besides any *.bd domain (the price check ignores the rest)
 */
@Validated
@ConfigurationProperties(prefix = "app.assistant")
public record AssistantProperties(
        boolean enabled,
        @NotNull Gateway gateway,
        String apiKey,
        @NotBlank String model,
        @NotBlank String baseUrl,
        boolean searchEnabled,
        boolean verdictEnabled,
        String thinkingLevel,
        @Min(0) @Max(100_000) int perUserPerHour,
        @Min(0) @Max(1_000_000) int globalPerDay,
        @NotNull Duration timeout,
        @Min(1) long maxImageBytes,
        @NotNull @DecimalMin(value = "1", inclusive = true) BigDecimal bdtPerUsd,
        List<String> bangladeshDomains
) {
    public enum Gateway { GEMINI, FAKE }

    public AssistantProperties {
        bangladeshDomains = bangladeshDomains == null ? List.of() : List.copyOf(bangladeshDomains);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** On, and able to answer: the fake needs no key, the real gateway does. */
    public boolean isAvailable() {
        return enabled && (gateway == Gateway.FAKE || hasApiKey());
    }

    public boolean isVerdictAvailable() {
        return isAvailable() && verdictEnabled;
    }

    /** Never print the key, even by accident through a log of this record. */
    @Override
    public String toString() {
        return "AssistantProperties[enabled=" + enabled + ", gateway=" + gateway + ", model=" + model
                + ", apiKey=" + (hasApiKey() ? "(set)" : "(missing)") + ", search=" + searchEnabled
                + ", verdict=" + verdictEnabled + ", perUserPerHour=" + perUserPerHour
                + ", globalPerDay=" + globalPerDay + ", timeout=" + timeout + "]";
    }
}
