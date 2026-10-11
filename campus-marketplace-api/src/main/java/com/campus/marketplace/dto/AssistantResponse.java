package com.campus.marketplace.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * One assistant answer. Plain text (line breaks only); the frontend never renders it as HTML.
 *
 * @param verdict           only for VERDICT; null otherwise. Decided by the server from the prices, not by the model
 * @param sources           the web pages the answer was grounded on (empty without web search)
 * @param cached            true when this answer came from the cache instead of a fresh Gemini call
 * @param unavailable       true when Gemini refused or failed (quota, overload, timeout): {@code answer} is then a
 *                          message to show as is, and nothing else is set
 * @param listingPriceUsd   VERDICT only: this listing's price
 * @param medianUsd         VERDICT only: the median of the Bangladeshi prices found, converted; null with fewer than 2
 * @param bdtPerUsd         VERDICT only: the rate the conversion used
 * @param typicalPriceLabel VERDICT only: "Typical price in Bangladesh: ≈ $102", or null
 * @param conversionNote    VERDICT only: "converted at 1 USD = 123 BDT, approximate"
 * @param foundPrices       VERDICT only: the Bangladeshi prices that counted
 */
public record AssistantResponse(
        String answer,
        Verdict verdict,
        List<Source> sources,
        String disclaimer,
        boolean cached,
        boolean unavailable,
        BigDecimal listingPriceUsd,
        BigDecimal medianUsd,
        BigDecimal bdtPerUsd,
        String typicalPriceLabel,
        String conversionNote,
        List<FoundPrice> foundPrices
) {
    public static final String DISCLAIMER_WEB = "AI estimate. Specs and prices may be wrong, check before you buy.";
    public static final String DISCLAIMER_KNOWLEDGE = "From AI knowledge, not web-checked. Check before you buy.";
    /** Shown when Gemini refuses or fails, whatever the reason. */
    public static final String UNAVAILABLE_MESSAGE = "Out of tokens, please contact admin. Admin E-mail: 2023100000351@seu.edu.bd";

    public enum Verdict { GOOD_DEAL, FAIR, OVERPRICED, NOT_ENOUGH_DATA }

    public record Source(String title, String url) {
    }

    /**
     * @param label "৳12,500 (≈ $102)"
     */
    public record FoundPrice(BigDecimal amountBdt, BigDecimal amountUsd, String condition, String sourceTitle,
                             String sourceUrl, String label) {
    }

    public static AssistantResponse text(String answer, List<Source> sources, String disclaimer) {
        return new AssistantResponse(answer, null, sources, disclaimer, false, false, null, null, null, null, null, List.of());
    }

    public static AssistantResponse unavailableAnswer() {
        return new AssistantResponse(UNAVAILABLE_MESSAGE, null, List.of(), null, false, true, null, null, null, null, null, List.of());
    }

    public AssistantResponse asCached() {
        return new AssistantResponse(answer, verdict, sources, disclaimer, true, unavailable, listingPriceUsd, medianUsd,
                bdtPerUsd, typicalPriceLabel, conversionNote, foundPrices);
    }
}
