package com.campus.marketplace.service;

import com.campus.marketplace.config.AssistantProperties;
import com.campus.marketplace.config.CacheNames;
import com.campus.marketplace.dto.AssistantRequest;
import com.campus.marketplace.dto.AssistantRequest.Mode;
import com.campus.marketplace.dto.AssistantResponse;
import com.campus.marketplace.dto.ListingResponse;
import com.campus.marketplace.exception.AssistantUnavailableException;
import com.campus.marketplace.exception.TooManyRequestsException;
import com.campus.marketplace.security.CurrentUserProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * The "Ask AI" shopping assistant. Answers about one public listing, using only its product facts (never anyone's
 * name, email or phone). SPECS without a need and VERDICT answers are the same for everyone and cached per listing,
 * mode and price ({@link CacheNames#ASSISTANT_ANSWERS}); ASK and SPECS with a need are personal and never cached.
 * <p>
 * When Gemini refuses or fails (quota used up, overloaded, timeout, anything), the student gets a normal 200 answer
 * with {@code unavailable=true} and a contact message, so the panel shows a message instead of an error. Logs carry
 * only the mode, listing id, latency and outcome, never prompts, answers or the key.
 */
@Slf4j
@Service
public class AssistantService {

    static final String EMPTY_ANSWER = "I couldn't find a good answer to that. Try asking in a different way.";
    static final String VERDICT_OFF = "The price check is turned off right now";

    private final AssistantProperties properties;
    private final AssistantGateway gateway;
    private final AssistantRateLimiter rateLimiter;
    private final ListingPhotoFetcher photoFetcher;
    private final PublicListingReader listingReader;
    private final CurrentUserProvider currentUser;
    private final CacheManager cacheManager;
    private final AssistantPromptBuilder promptBuilder;

    public AssistantService(AssistantProperties properties, AssistantGateway gateway, AssistantRateLimiter rateLimiter,
                            ListingPhotoFetcher photoFetcher, PublicListingReader listingReader,
                            CurrentUserProvider currentUser, CacheManager cacheManager, ObjectMapper objectMapper) {
        this.properties = properties;
        this.gateway = gateway;
        this.rateLimiter = rateLimiter;
        this.photoFetcher = photoFetcher;
        this.listingReader = listingReader;
        this.currentUser = currentUser;
        this.cacheManager = cacheManager;
        this.promptBuilder = new AssistantPromptBuilder(objectMapper);
    }

    /**
     * @throws AssistantUnavailableException 503 when the assistant (or, for VERDICT, the price check) is switched off
     *                                       or not configured
     * @throws TooManyRequestsException      429, only if a limit is configured (none is by default)
     */
    public AssistantResponse ask(Long listingId, AssistantRequest request) {
        if (!properties.isAvailable()) {
            throw AssistantUnavailableException.off();
        }
        if (request.mode() == Mode.VERDICT && !properties.isVerdictAvailable()) {
            throw new AssistantUnavailableException(VERDICT_OFF);
        }
        Long userId = currentUser.requireId();
        // The public view: a hidden, unpaid or missing listing is a 404 here too. Already a DTO, mapped in its
        // own transaction, so nothing lazy is touched below.
        ListingResponse listing = listingReader.publicView(listingId);

        Cache cache = isShared(request) ? cacheManager.getCache(CacheNames.ASSISTANT_ANSWERS) : null;
        String key = cacheKey(listingId, request.mode(), listing);
        if (cache != null) {
            AssistantResponse hit = cache.get(key, AssistantResponse.class);
            if (hit != null) {
                log.info("assistant mode={} listing={} status=cached latencyMs=0", request.mode(), listingId);
                return hit.asCached();
            }
        }

        try {
            rateLimiter.acquire(userId);
        } catch (TooManyRequestsException e) {
            log.info("assistant mode={} listing={} status=rate_limited latencyMs=0", request.mode(), listingId);
            throw e;
        }
        long started = System.nanoTime();
        AssistantPromptBuilder.ProductFacts facts = facts(listing);
        AssistantGateway.Image photo = photoFetcher.fetch(facts.imageUrl()).orElse(null);
        boolean search = properties.searchEnabled();
        AssistantGateway.Reply reply;
        try {
            reply = gateway.generate(promptBuilder.build(facts, request, photo, search));
        } catch (AssistantUnavailableException e) {
            log.info("assistant mode={} listing={} status=provider_error latencyMs={}", request.mode(), listingId, millisSince(started));
            return AssistantResponse.unavailableAnswer();
        }

        AssistantResponse response = request.mode() == Mode.VERDICT
                ? verdict(listing, reply)
                : answer(reply, search);
        boolean answered = !EMPTY_ANSWER.equals(response.answer());
        if (cache != null && answered) {
            cache.put(key, response);
        }
        log.info("assistant mode={} listing={} status={} latencyMs={} photo={} sources={}", request.mode(), listingId,
                answered ? "ok" : "empty", millisSince(started), photo != null, response.sources().size());
        return response;
    }

    /** Only answers that are the same for everyone are shared. */
    static boolean isShared(AssistantRequest request) {
        return request.mode() == Mode.VERDICT || (request.mode() == Mode.SPECS && !request.hasNeed());
    }

    /** {@code id:MODE:price}. The listing id comes first so all of a listing's entries can be evicted by prefix. */
    static String cacheKey(Long listingId, Mode mode, ListingResponse listing) {
        return listingId + ":" + mode + ":" + listing.price().setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static AssistantResponse answer(AssistantGateway.Reply reply, boolean search) {
        String text = plainText(reply.text());
        return AssistantResponse.text(text.isBlank() ? EMPTY_ANSWER : text, sources(reply),
                search ? AssistantResponse.DISCLAIMER_WEB : AssistantResponse.DISCLAIMER_KNOWLEDGE);
    }

    /**
     * The model only lists prices; the server decides. Prices count only from Bangladeshi sites the answer was
     * grounded on, so without any Bangladeshi grounding source the verdict is NOT_ENOUGH_DATA.
     */
    private AssistantResponse verdict(ListingResponse listing, AssistantGateway.Reply reply) {
        List<AssistantResponse.Source> sources = sources(reply);
        PriceVerdict.Extracted extracted = PriceVerdict.extract(reply.text() == null ? "" : reply.text());
        List<String> groundedDomains = reply.sources().stream()
                .flatMap(s -> PriceVerdict.domainOf(s.title(), s.url()).stream())
                .toList();
        List<PriceVerdict.RawPrice> bangladeshi = extracted.prices()
                .map(prices -> PriceVerdict.bangladeshiPrices(prices, groundedDomains, properties.bangladeshDomains()))
                .orElse(List.of());
        PriceVerdict.Decision decision = PriceVerdict.decide(listing.price(), bangladeshi, properties.bdtPerUsd());

        String explanation = plainText(extracted.explanation());
        String answer = decision.message() == null
                ? (explanation.isBlank() ? EMPTY_ANSWER : explanation)
                : decision.message();
        return new AssistantResponse(answer, decision.verdict(), sources, AssistantResponse.DISCLAIMER_WEB, false, false,
                listing.price(), decision.medianUsd(), properties.bdtPerUsd(),
                decision.medianUsd() == null ? null : PriceVerdict.typicalLabel(decision.medianUsd()),
                PriceVerdict.conversionNote(properties.bdtPerUsd()), decision.prices());
    }

    private static List<AssistantResponse.Source> sources(AssistantGateway.Reply reply) {
        return reply.sources().stream().map(s -> new AssistantResponse.Source(s.title(), s.url())).toList();
    }

    private AssistantPromptBuilder.ProductFacts facts(ListingResponse listing) {
        String photo = listing.images() == null ? null : listing.images().stream()
                .min(Comparator.comparingInt(ListingResponse.Image::position))
                .map(ListingResponse.Image::url)
                .orElse(null);
        return new AssistantPromptBuilder.ProductFacts(
                listing.id(),
                listing.title(),
                listing.description(),
                listing.category().name(),
                listing.itemCondition() == null ? null : listing.itemCondition().name(),
                listing.price(),
                "USD",
                photo != null ? photo : listing.imageUrl());
    }

    /** Strips the Markdown the model sometimes adds despite being told not to, since the page shows plain text. */
    static String plainText(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("\r\n", "\n")
                .replace("**", "")
                .replace("__", "")
                .replaceAll("(?m)^#{1,6}\\s+", "")
                .replaceAll("(?m)^(\\s*)[*•]\\s+", "$1- ")
                .replace("`", "")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
