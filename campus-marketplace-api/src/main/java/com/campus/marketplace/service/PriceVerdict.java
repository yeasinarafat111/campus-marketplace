package com.campus.marketplace.service;

import com.campus.marketplace.dto.AssistantResponse.FoundPrice;
import com.campus.marketplace.dto.AssistantResponse.Verdict;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "Is it a good buy?" maths. The model only reports the Bangladeshi prices it found (in BDT, in a fixed JSON
 * block); everything else happens here, in BigDecimal: conversion to USD, the median, and the verdict from fixed
 * thresholds. A price counts only if its site is Bangladeshi ({@code *.bd} or the allowlist) and that site is among
 * the pages the answer was actually grounded on, so a made-up price from a made-up page can't sway the verdict.
 */
public final class PriceVerdict {

    static final String BLOCK_START = "PRICES_JSON_START";
    static final String BLOCK_END = "PRICES_JSON_END";
    static final String NO_PRICES = "No Bangladeshi second-hand prices found for this item";
    static final String ONE_PRICE = "Only one Bangladeshi second-hand price found for this item, which is not enough to judge.";

    /** At or below 90% of the median is a good deal; up to 110% is fair; above is overpriced. */
    static final BigDecimal GOOD_DEAL_MAX = new BigDecimal("0.90");
    static final BigDecimal FAIR_MAX = new BigDecimal("1.10");
    static final int MIN_PRICES = 2;
    static final int MAX_PRICES = 10;
    private static final BigDecimal MIN_BDT = BigDecimal.ONE;
    private static final BigDecimal MAX_BDT = new BigDecimal("10000000");

    private static final Pattern BLOCK = Pattern.compile(
            Pattern.quote(BLOCK_START) + "\\s*(?:```(?:json)?)?\\s*(.*?)\\s*(?:```)?\\s*" + Pattern.quote(BLOCK_END),
            Pattern.DOTALL);
    /** Gemini's grounding links are redirects through this host; the real site is then in the title. */
    private static final String REDIRECT_HOST = "vertexaisearch.cloud.google.com";

    private static final ObjectMapper JSON = new ObjectMapper();

    private PriceVerdict() {
    }

    /** A price as the model reported it. */
    record RawPrice(BigDecimal amountBdt, String condition, String sourceTitle, String sourceUrl) {
    }

    /** @param prices empty if the block was missing or wasn't a JSON array */
    record Extracted(Optional<List<RawPrice>> prices, String explanation) {
    }

    record Decision(Verdict verdict, BigDecimal medianUsd, List<FoundPrice> prices, String message) {
    }

    /** Pulls the price block out of the model's text. The rest is the explanation shown to the student. */
    static Extracted extract(String text) {
        Matcher m = BLOCK.matcher(text);
        if (!m.find()) {
            return new Extracted(Optional.empty(), text.strip());
        }
        String explanation = (text.substring(0, m.start()) + text.substring(m.end())).strip();
        try {
            JsonNode array = JSON.readTree(m.group(1));
            if (array == null || !array.isArray()) {
                return new Extracted(Optional.empty(), explanation);
            }
            List<RawPrice> prices = new ArrayList<>();
            for (JsonNode item : array) {
                if (prices.size() >= MAX_PRICES) {
                    break;
                }
                amount(item.path("amountBdt")).ifPresent(amount -> prices.add(new RawPrice(amount,
                        item.path("condition").asText(""), item.path("sourceTitle").asText(""),
                        item.path("sourceUrl").asText(""))));
            }
            return new Extracted(Optional.of(List.copyOf(prices)), explanation);
        } catch (Exception e) {
            return new Extracted(Optional.empty(), explanation);
        }
    }

    /** A number, or a string like "12,500" or "৳ 12500". Out-of-range or unreadable amounts are dropped. */
    private static Optional<BigDecimal> amount(JsonNode node) {
        BigDecimal value = null;
        if (node.isNumber()) {
            value = node.decimalValue();
        } else if (node.isTextual()) {
            String digits = node.asText().replaceAll("(?i)[,\\s৳]|tk\\.?|bdt", "");
            if (digits.matches("\\d+(\\.\\d+)?")) {
                value = new BigDecimal(digits);
            }
        }
        if (value == null || value.compareTo(MIN_BDT) < 0 || value.compareTo(MAX_BDT) > 0) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    /** The site a price or grounding source is from: the URL's host, or the title when the URL is a redirect. */
    static Optional<String> domainOf(String title, String url) {
        String host = null;
        try {
            if (url != null && !url.isBlank()) {
                host = URI.create(url.strip()).getHost();
            }
        } catch (IllegalArgumentException ignored) {
            // fall back to the title
        }
        if (host == null || host.equalsIgnoreCase(REDIRECT_HOST)) {
            host = title;
        }
        if (host == null) {
            return Optional.empty();
        }
        host = host.strip().toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        return host.matches("[a-z0-9-]+(\\.[a-z0-9-]+)+") ? Optional.of(host) : Optional.empty();
    }

    static boolean isBangladeshi(String domain, Collection<String> allowlist) {
        if (domain.endsWith(".bd")) {
            return true;
        }
        return allowlist.stream().map(d -> d.strip().toLowerCase(Locale.ROOT)).filter(d -> !d.isEmpty())
                .anyMatch(d -> domain.equals(d) || domain.endsWith("." + d));
    }

    private static boolean sameSite(String a, String b) {
        return a.equals(b) || a.endsWith("." + b) || b.endsWith("." + a);
    }

    /** Keeps the prices from Bangladeshi sites that the answer was grounded on. */
    static List<RawPrice> bangladeshiPrices(List<RawPrice> prices, List<String> groundedDomains, Collection<String> allowlist) {
        List<String> grounded = groundedDomains.stream().filter(d -> isBangladeshi(d, allowlist)).toList();
        return prices.stream().filter(p -> domainOf(p.sourceTitle(), p.sourceUrl())
                        .filter(d -> isBangladeshi(d, allowlist))
                        .filter(d -> grounded.stream().anyMatch(g -> sameSite(d, g)))
                        .isPresent())
                .toList();
    }

    static BigDecimal toUsd(BigDecimal bdt, BigDecimal bdtPerUsd) {
        return bdt.divide(bdtPerUsd, 2, RoundingMode.HALF_UP);
    }

    /** The middle value; with an even count, the mean of the two middle ones (to the cent, half-up). */
    static BigDecimal median(List<BigDecimal> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("no values");
        }
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid).setScale(2, RoundingMode.HALF_UP);
        }
        return sorted.get(mid - 1).add(sorted.get(mid)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    static Verdict verdictFor(BigDecimal listingUsd, BigDecimal medianUsd) {
        if (listingUsd.compareTo(medianUsd.multiply(GOOD_DEAL_MAX)) <= 0) {
            return Verdict.GOOD_DEAL;
        }
        if (listingUsd.compareTo(medianUsd.multiply(FAIR_MAX)) <= 0) {
            return Verdict.FAIR;
        }
        return Verdict.OVERPRICED;
    }

    /** The verdict for a listing price given the Bangladeshi prices that counted. */
    static Decision decide(BigDecimal listingUsd, List<RawPrice> bangladeshi, BigDecimal bdtPerUsd) {
        List<FoundPrice> found = bangladeshi.stream()
                .map(p -> {
                    BigDecimal usd = toUsd(p.amountBdt(), bdtPerUsd);
                    return new FoundPrice(p.amountBdt(), usd, p.condition(), p.sourceTitle(), p.sourceUrl(),
                            bdtLabel(p.amountBdt()) + " (≈ " + usdLabel(usd) + ")");
                })
                .sorted(Comparator.comparing(FoundPrice::amountUsd))
                .toList();
        if (found.isEmpty()) {
            return new Decision(Verdict.NOT_ENOUGH_DATA, null, found, NO_PRICES);
        }
        if (found.size() < MIN_PRICES) {
            return new Decision(Verdict.NOT_ENOUGH_DATA, null, found, ONE_PRICE);
        }
        BigDecimal median = median(found.stream().map(FoundPrice::amountUsd).toList());
        return new Decision(verdictFor(listingUsd, median), median, found, null);
    }

    static String bdtLabel(BigDecimal bdt) {
        return "৳" + NumberFormat.getIntegerInstance(Locale.US).format(bdt.setScale(0, RoundingMode.HALF_UP));
    }

    static String usdLabel(BigDecimal usd) {
        return "$" + NumberFormat.getIntegerInstance(Locale.US).format(usd.setScale(0, RoundingMode.HALF_UP));
    }

    static String typicalLabel(BigDecimal medianUsd) {
        return "Typical price in Bangladesh: ≈ " + usdLabel(medianUsd);
    }

    static String conversionNote(BigDecimal bdtPerUsd) {
        return "converted at 1 USD = " + bdtPerUsd.stripTrailingZeros().toPlainString() + " BDT, approximate";
    }
}
