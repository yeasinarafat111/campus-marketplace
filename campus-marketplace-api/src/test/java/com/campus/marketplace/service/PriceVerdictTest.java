package com.campus.marketplace.service;

import com.campus.marketplace.dto.AssistantResponse.Verdict;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PriceVerdictTest {

    private static final BigDecimal RATE = new BigDecimal("120");
    private static final List<String> ALLOW = List.of("bikroy.com", "daraz.com.bd", "startech.com.bd", "ryans.com");

    private static PriceVerdict.RawPrice bdt(String amount, String site) {
        return new PriceVerdict.RawPrice(new BigDecimal(amount), "used", site, "https://" + site + "/ad/1");
    }

    // --- conversion ---

    @Test
    void convertsBdtToUsd_halfUpToTheCent() {
        assertThat(PriceVerdict.toUsd(new BigDecimal("12500"), RATE)).isEqualByComparingTo("104.17"); // 104.1666…
        assertThat(PriceVerdict.toUsd(new BigDecimal("1203"), RATE)).isEqualByComparingTo("10.03");   // 10.025 → up
        assertThat(PriceVerdict.toUsd(new BigDecimal("6000"), RATE)).isEqualByComparingTo("50.00");
    }

    @Test
    void labelsShowTakaAndRoundedDollars() {
        assertThat(PriceVerdict.bdtLabel(new BigDecimal("12500"))).isEqualTo("৳12,500");
        assertThat(PriceVerdict.usdLabel(new BigDecimal("104.50"))).isEqualTo("$105");
        assertThat(PriceVerdict.typicalLabel(new BigDecimal("101.6"))).isEqualTo("Typical price in Bangladesh: ≈ $102");
        assertThat(PriceVerdict.conversionNote(new BigDecimal("123.00"))).isEqualTo("converted at 1 USD = 123 BDT, approximate");
    }

    // --- median ---

    @Test
    void median_oddCount_isTheMiddleValue() {
        assertThat(PriceVerdict.median(List.of(new BigDecimal("30"), new BigDecimal("10"), new BigDecimal("20"))))
                .isEqualByComparingTo("20.00");
    }

    @Test
    void median_evenCount_isTheMeanOfTheTwoMiddle_halfUp() {
        assertThat(PriceVerdict.median(List.of(new BigDecimal("10.00"), new BigDecimal("40.00"), new BigDecimal("20.01"), new BigDecimal("30.00"))))
                .isEqualByComparingTo("25.01"); // (20.01 + 30.00) / 2 = 25.005 → 25.01
    }

    // --- thresholds ---

    @Test
    void verdictThresholds_atAndAroundTheBoundaries() {
        BigDecimal median = new BigDecimal("100.00");
        assertThat(PriceVerdict.verdictFor(new BigDecimal("80.00"), median)).isEqualTo(Verdict.GOOD_DEAL);
        assertThat(PriceVerdict.verdictFor(new BigDecimal("90.00"), median)).isEqualTo(Verdict.GOOD_DEAL); // exactly 90%
        assertThat(PriceVerdict.verdictFor(new BigDecimal("90.01"), median)).isEqualTo(Verdict.FAIR);
        assertThat(PriceVerdict.verdictFor(new BigDecimal("100.00"), median)).isEqualTo(Verdict.FAIR);
        assertThat(PriceVerdict.verdictFor(new BigDecimal("110.00"), median)).isEqualTo(Verdict.FAIR);     // exactly 110%
        assertThat(PriceVerdict.verdictFor(new BigDecimal("110.01"), median)).isEqualTo(Verdict.OVERPRICED);
    }

    @Test
    void decide_usesTheMedianOfConvertedPrices() {
        // 6000, 12000, 24000 BDT at 120 → $50, $100, $200; median $100.
        List<PriceVerdict.RawPrice> prices = List.of(bdt("12000", "bikroy.com"), bdt("6000", "bikroy.com"), bdt("24000", "daraz.com.bd"));
        PriceVerdict.Decision good = PriceVerdict.decide(new BigDecimal("85"), prices, RATE);
        assertThat(good.verdict()).isEqualTo(Verdict.GOOD_DEAL);
        assertThat(good.medianUsd()).isEqualByComparingTo("100.00");
        assertThat(good.prices()).extracting(p -> p.label()).containsExactly("৳6,000 (≈ $50)", "৳12,000 (≈ $100)", "৳24,000 (≈ $200)");
        assertThat(PriceVerdict.decide(new BigDecimal("105"), prices, RATE).verdict()).isEqualTo(Verdict.FAIR);
        assertThat(PriceVerdict.decide(new BigDecimal("150"), prices, RATE).verdict()).isEqualTo(Verdict.OVERPRICED);
    }

    // --- not enough data ---

    @Test
    void noPrices_isNotEnoughData_withTheFixedMessage() {
        PriceVerdict.Decision d = PriceVerdict.decide(new BigDecimal("50"), List.of(), RATE);
        assertThat(d.verdict()).isEqualTo(Verdict.NOT_ENOUGH_DATA);
        assertThat(d.message()).isEqualTo("No Bangladeshi second-hand prices found for this item");
        assertThat(d.medianUsd()).isNull();
    }

    @Test
    void onePrice_isNotEnoughData_butIsShown() {
        PriceVerdict.Decision d = PriceVerdict.decide(new BigDecimal("50"), List.of(bdt("6000", "bikroy.com")), RATE);
        assertThat(d.verdict()).isEqualTo(Verdict.NOT_ENOUGH_DATA);
        assertThat(d.prices()).hasSize(1);
        assertThat(d.medianUsd()).isNull();
    }

    // --- parsing ---

    @Test
    void extract_readsTheBlock_andKeepsTheRestAsExplanation() {
        String text = """
                PRICES_JSON_START
                [{"amountBdt": 12500, "condition": "used", "sourceTitle": "bikroy.com", "sourceUrl": "https://bikroy.com/a"},
                 {"amountBdt": "৳ 9,800", "condition": "used", "sourceTitle": "bikroy.com", "sourceUrl": "https://bikroy.com/b"}]
                PRICES_JSON_END
                - Check the battery""";
        PriceVerdict.Extracted e = PriceVerdict.extract(text);
        assertThat(e.prices()).isPresent();
        assertThat(e.prices().get()).extracting(PriceVerdict.RawPrice::amountBdt)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("12500"), new BigDecimal("9800"));
        assertThat(e.explanation()).isEqualTo("- Check the battery");
    }

    @Test
    void extract_toleratesACodeFence() {
        String text = "PRICES_JSON_START\n```json\n[{\"amountBdt\": 5000, \"sourceTitle\": \"bikroy.com\"}]\n```\nPRICES_JSON_END\nok";
        assertThat(PriceVerdict.extract(text).prices()).hasValueSatisfying(p -> assertThat(p).hasSize(1));
    }

    @Test
    void extract_failures_giveNoPrices() {
        assertThat(PriceVerdict.extract("VERDICT: GOOD_DEAL\nIt is cheap!").prices()).isEmpty();          // no block
        assertThat(PriceVerdict.extract("PRICES_JSON_START\n[{amountBdt: oops\nPRICES_JSON_END").prices()).isEmpty(); // bad JSON
        assertThat(PriceVerdict.extract("PRICES_JSON_START\n{\"amountBdt\": 5}\nPRICES_JSON_END").prices()).isEmpty(); // not an array
        assertThat(PriceVerdict.extract("PRICES_JSON_START\n[{\"amountBdt\": 5000}]").prices()).isEmpty();            // no end marker
    }

    @Test
    void extract_dropsUnreadableOrOutOfRangeAmounts() {
        String text = "PRICES_JSON_START\n[{\"amountBdt\": \"about five thousand\"}, {\"amountBdt\": -3}, {\"amountBdt\": 0},"
                + " {\"amountBdt\": 99999999999}, {\"amountBdt\": 4500}]\nPRICES_JSON_END";
        assertThat(PriceVerdict.extract(text).prices()).hasValueSatisfying(p -> assertThat(p).hasSize(1));
    }

    @Test
    void extract_capsTheNumberOfPrices() {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            items.append(i == 0 ? "" : ",").append("{\"amountBdt\": ").append(1000 + i).append('}');
        }
        String text = "PRICES_JSON_START\n[" + items + "]\nPRICES_JSON_END";
        assertThat(PriceVerdict.extract(text).prices()).hasValueSatisfying(p -> assertThat(p).hasSize(PriceVerdict.MAX_PRICES));
    }

    // --- Bangladeshi sources ---

    @Test
    void domainOf_usesTheTitleForGeminiRedirectLinks() {
        assertThat(PriceVerdict.domainOf("bikroy.com", "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AbC"))
                .contains("bikroy.com");
        assertThat(PriceVerdict.domainOf("anything", "https://www.Daraz.com.bd/products/x")).contains("daraz.com.bd");
        assertThat(PriceVerdict.domainOf("Some shop page", "")).isEmpty();
    }

    @Test
    void bangladeshiMeans_dotBd_orTheAllowlist() {
        assertThat(PriceVerdict.isBangladeshi("anyshop.com.bd", ALLOW)).isTrue();
        assertThat(PriceVerdict.isBangladeshi("bikroy.com", ALLOW)).isTrue();
        assertThat(PriceVerdict.isBangladeshi("m.bikroy.com", ALLOW)).isTrue();
        assertThat(PriceVerdict.isBangladeshi("amazon.com", ALLOW)).isFalse();
        assertThat(PriceVerdict.isBangladeshi("notbikroy.com", ALLOW)).isFalse();
        assertThat(PriceVerdict.isBangladeshi("flipkart.in", ALLOW)).isFalse();
    }

    @Test
    void onlyPricesFromGroundedBangladeshiSitesCount() {
        List<PriceVerdict.RawPrice> prices = List.of(
                bdt("5000", "bikroy.com"),        // Bangladeshi and grounded
                bdt("5200", "startech.com.bd"),   // Bangladeshi but not among the grounded pages
                bdt("4000", "amazon.com"));       // foreign
        assertThat(PriceVerdict.bangladeshiPrices(prices, List.of("bikroy.com", "amazon.com"), ALLOW))
                .extracting(PriceVerdict.RawPrice::sourceTitle).containsExactly("bikroy.com");
        // No Bangladeshi grounding source at all: nothing counts.
        assertThat(PriceVerdict.bangladeshiPrices(prices, List.of("amazon.com", "ebay.com"), ALLOW)).isEmpty();
    }
}
