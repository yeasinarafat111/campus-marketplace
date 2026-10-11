package com.campus.marketplace.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.time.Duration;
import java.util.List;

import static com.campus.marketplace.config.CacheNames.ASSISTANT_ANSWERS;
import static com.campus.marketplace.config.CacheNames.CATEGORY_COUNTS;
import static com.campus.marketplace.config.CacheNames.FEATURED_LISTINGS;
import static com.campus.marketplace.config.CacheNames.FEE_QUOTE;
import static com.campus.marketplace.config.CacheNames.LISTING_BY_ID;
import static com.campus.marketplace.config.CacheNames.USER_PROFILE;

/**
 * In-process (Caffeine) caches for hot, shareable reads, one cache per name so each has its own TTL and size.
 *
 * <pre>
 * cache              key         holds                              TTL / max
 * listingById        id          ListingResponse (public view)      5 min / 2000
 * featuredListings   'home'      the featured ListingResponses      1 min / 1
 * categoryCounts     'all'       Map&lt;Category, Long&gt; for the chips   5 min / 1
 * userProfile        user id     CurrentUserResponse                10 min / 5000
 * feeQuote           listing id  FeeQuote                           5 min / 2000
 * assistantAnswers   'id:mode:price'  AssistantResponse (SPECS without a need, VERDICT)  6 h / 2000
 * </pre>
 *
 * <h3>Rules that keep this correct</h3>
 * <ol>
 *   <li><b>Cache DTOs (immutable records), never JPA entities.</b> Entities hold lazy proxies that break
 *       outside the session.</li>
 *   <li><b>Self-invocation bypasses the proxy.</b> A {@code @Cacheable} method called from its own class is
 *       not cached, so cached reads live on beans called from controllers and other beans
 *       ({@code PublicListingReader}, {@code PaymentService.quote}, {@code UserService.getMe}).</li>
 *   <li><b>Don't cache the personalised parts.</b> {@code reservedByMe}, the reservation deadline and the
 *       other side's contact depend on who is asking. Only the public {@code ListingResponse} is cached; the
 *       viewer-specific fields are added after the cache lookup, and only when the viewer could be a party.</li>
 *   <li><b>Don't cache arbitrary search pages.</b> Every combination of keyword, filters, sort and page is a
 *       separate key, so the hit rate would be low and evicting them correctly on a write is impossible.
 *       (The one fixed list that matters, the featured row, has its own cache.)</li>
 *   <li><b>Evict on every write that changes what is cached.</b> Annotated writes use {@code @Caching(evict)}.
 *       The webhook path has no method argument to key on, so it goes through {@code ListingCacheEvictor},
 *       which evicts <em>after the transaction commits</em>, so a rollback can't leave the cache ahead of the
 *       database and a reader can't re-cache pre-commit data. For the same reason the cache advice is
 *       ordered outside the transaction advice below, so annotated evictions also run after commit.</li>
 *   <li><b>Time-based staleness.</b> {@code featured} is computed when the response is built, from
 *       {@code featuredUntil}, so a cached entry can say "featured" for up to its TTL after the feature has
 *       expired (and the featured list can include such a listing for up to a minute). That is acceptable at
 *       these TTLs.</li>
 *   <li><b>Several instances.</b> Caffeine is per instance, so the short TTLs bound how long two instances can
 *       disagree after a write handled by the other one. If the service ever scales out a lot, the next step is
 *       Redis ({@code spring-boot-starter-data-redis}) with the same annotations.</li>
 * </ol>
 * Statistics are recorded on every cache. {@code /actuator/caches} and {@code /actuator/metrics} are
 * deliberately left unexposed (only {@code health} is).
 * <p>
 * {@code app.cache.enabled=false} swaps in a no-op manager; the test profile uses it so tests that change
 * data directly through repositories never see a stale cached copy.
 */
@Configuration
// Cache advice outside the (default, lowest-precedence) transaction advice: lookups don't open a transaction
// on a hit, and evictions run after the transaction has committed.
@EnableCaching(order = Ordered.LOWEST_PRECEDENCE - 10)
public class CacheConfig {

    @Bean
    public CacheManager cacheManager(@Value("${app.cache.enabled:true}") boolean enabled) {
        if (!enabled) {
            return new NoOpCacheManager();
        }
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                cache(LISTING_BY_ID, Duration.ofMinutes(5), 2_000),
                cache(FEATURED_LISTINGS, Duration.ofMinutes(1), 1),
                cache(CATEGORY_COUNTS, Duration.ofMinutes(5), 1),
                cache(USER_PROFILE, Duration.ofMinutes(10), 5_000),
                cache(FEE_QUOTE, Duration.ofMinutes(5), 2_000),
                // Long on purpose: every miss is a Gemini call against a small free daily quota. Evicted by listing
                // id prefix when the listing changes (ListingCacheEvictor.evictAssistantAnswers).
                cache(ASSISTANT_ANSWERS, Duration.ofHours(6), 2_000)));
        return manager;
    }

    private static CaffeineCache cache(String name, Duration ttl, long maxSize) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .recordStats()
                .build());
    }
}
