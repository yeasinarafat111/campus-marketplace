package com.campus.marketplace.controller;

import com.campus.marketplace.config.CacheNames;
import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.Role;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.OrderRepository;
import com.campus.marketplace.repository.PaymentRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.JwtService;
import com.campus.marketplace.security.UserPrincipal;
import com.campus.marketplace.service.CheckoutGateway;
import com.campus.marketplace.service.ListingCacheEvictor;
import com.campus.marketplace.service.MailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.Stripe;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The caches switched on: hits, eviction on every write that changes what is cached, and the viewer-specific
 * parts of a listing staying correct for different callers on top of one shared cached copy.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.cache.enabled=true")
class CachingTest {

    private static final String SECRET = "whsec_test_secret"; // matches src/test/resources/application.yml

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CacheManager cacheManager;
    @Autowired private UserRepository userRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private JwtService jwtService;
    @Autowired private ListingCacheEvictor evictor;
    @Autowired private TransactionTemplate transactions;
    @MockitoSpyBean private ListingRepository listingRepository;
    @MockitoBean private CheckoutGateway checkoutGateway;
    @MockitoBean private MailService mailService;

    private User seller;
    private User buyer;
    private User other;
    private User admin;
    private Listing lamp;
    private final AtomicInteger sessions = new AtomicInteger();

    @BeforeEach
    void setUp() {
        cleanUp();
        sessions.set(0);
        seller = saveUser("seller@seu.edu.bd", "Sally Seller", "+8801711111111", Role.STUDENT);
        buyer = saveUser("buyer@seu.edu.bd", "Bob Buyer", "+8801722222222", Role.STUDENT);
        other = saveUser("other@seu.edu.bd", "Olga Other", null, Role.STUDENT);
        admin = saveUser("admin@seu.edu.bd", "Ada Admin", null, Role.ADMIN);
        lamp = listingRepository.save(Listing.builder().title("Desk lamp").price(new BigDecimal("12.00"))
                .category(Category.OTHER).owner(seller).build());
        when(checkoutGateway.createCheckoutSession(any())).thenAnswer(inv ->
                new CheckoutGateway.CheckoutSession("cs_" + sessions.incrementAndGet(), "https://checkout.stripe.com/x"));
        clearCaches();
        snapshotStats();
        clearInvocations(listingRepository);
    }

    @AfterEach
    void cleanUp() {
        clearCaches();
        orderRepository.deleteAll();
        paymentRepository.deleteAll();
        listingRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---- listingById ----

    @Test
    void twoReadsOfAListing_hitTheDatabaseOnce() throws Exception {
        getListing(lamp, null).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Desk lamp"));
        getListing(lamp, null).andExpect(status().isOk());
        getListing(lamp, null).andExpect(status().isOk());

        verify(listingRepository, times(1)).findWithOwnerById(lamp.getId());
        assertThat(hits(CacheNames.LISTING_BY_ID)).isEqualTo(2);
        assertThat(misses(CacheNames.LISTING_BY_ID)).isEqualTo(1);
    }

    @Test
    void anUnknownListing_is404_andIsNotCached() throws Exception {
        getListing(999_999L, null).andExpect(status().isNotFound());
        getListing(999_999L, null).andExpect(status().isNotFound());
        verify(listingRepository, times(2)).findWithOwnerById(999_999L);
    }

    @Test
    void update_evictsTheListingAndItsFeeQuote() throws Exception {
        getListing(lamp, null).andExpect(jsonPath("$.price").value(12.0));
        fees(buyer).andExpect(jsonPath("$.reserveCents").value(50));
        clearInvocations(listingRepository);

        mockMvc.perform(as(put("/api/listings/{id}", lamp.getId()), seller).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Desk lamp\",\"price\":150,\"category\":\"OTHER\"}"))
                .andExpect(status().isOk());

        getListing(lamp, null).andExpect(jsonPath("$.price").value(150.0));
        verify(listingRepository, times(1)).findWithOwnerById(lamp.getId());
        fees(buyer).andExpect(jsonPath("$.reserveCents").value(750)); // 5% of 150, not the cached 3% quote
    }

    @Test
    void markSold_andDelete_evict() throws Exception {
        getListing(lamp, null).andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(as(patch("/api/listings/{id}/sold", lamp.getId()), seller)).andExpect(status().isOk());
        getListing(lamp, null).andExpect(jsonPath("$.status").value("SOLD"));

        mockMvc.perform(as(delete("/api/listings/{id}", lamp.getId()), seller)).andExpect(status().isNoContent());
        getListing(lamp, null).andExpect(status().isNotFound());
    }

    @Test
    void adminDelete_evicts() throws Exception {
        getListing(lamp, null).andExpect(status().isOk());
        mockMvc.perform(as(delete("/api/admin/listings/{id}", lamp.getId()), admin)).andExpect(status().isNoContent());
        getListing(lamp, null).andExpect(status().isNotFound());
    }

    @Test
    void theStripeWebhook_evictsWhatItChanges() throws Exception {
        getListing(lamp, null).andExpect(jsonPath("$.reserved").value(false)).andExpect(jsonPath("$.featured").value(false));
        featured().andExpect(jsonPath("$.length()").value(0));

        // Feature: the cached listing and the cached featured row both change.
        completePayment(checkoutSession(seller, "FEATURE_LISTING"), "pi_1");
        getListing(lamp, null).andExpect(jsonPath("$.featured").value(true));
        featured().andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].title").value("Desk lamp"));

        // Reserve: the cached public copy now says reserved.
        completePayment(checkoutSession(buyer, "RESERVE_ITEM"), "pi_2");
        getListing(lamp, null).andExpect(jsonPath("$.reserved").value(true));

        // Buy: sold, and gone from the featured row.
        completePayment(checkoutSession(buyer, "PURCHASE"), "pi_3");
        getListing(lamp, null).andExpect(jsonPath("$.status").value("SOLD"));
        featured().andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void cancelRelease_andExpiry_evict() throws Exception {
        completePayment(checkoutSession(buyer, "RESERVE_ITEM"), "pi_2");
        getListing(lamp, null).andExpect(jsonPath("$.reserved").value(true));

        mockMvc.perform(as(post("/api/listings/{id}/reservation/release", lamp.getId()), buyer)).andExpect(status().isOk());
        getListing(lamp, null).andExpect(jsonPath("$.reserved").value(false));

        completePayment(checkoutSession(other, "RESERVE_ITEM"), "pi_4");
        getListing(lamp, null).andExpect(jsonPath("$.reserved").value(true));
        Listing l = listingRepository.findById(lamp.getId()).orElseThrow();
        l.setReservationExpiresAt(Instant.now().minusSeconds(1));
        listingRepository.save(l);
        // The sweep frees it and the next read must not serve the cached "reserved" copy.
        evictAndSweep();
        getListing(lamp, null).andExpect(jsonPath("$.reserved").value(false));
    }

    // ---- viewer-specific parts on top of the shared cached copy ----

    @Test
    void viewerSpecificFields_areCorrectForEachCaller_whileOneCachedCopyServesThemAll() throws Exception {
        completePayment(checkoutSession(buyer, "RESERVE_ITEM"), "pi_2");
        clearInvocations(listingRepository);

        // Anonymous and unrelated viewers: the cached public copy, nothing personal.
        getListing(lamp, null).andExpect(jsonPath("$.reserved").value(true))
                .andExpect(jsonPath("$.reservedByMe").value(false))
                .andExpect(jsonPath("$.counterparty").doesNotExist());
        // The reserver: their own fields, with the seller's phone.
        getListing(lamp, buyer).andExpect(jsonPath("$.reservedByMe").value(true))
                .andExpect(jsonPath("$.counterparty.phone").value("+8801711111111"))
                .andExpect(jsonPath("$.reservationExpiresAt").isNotEmpty());
        // The seller: the reserver's contact.
        getListing(lamp, seller).andExpect(jsonPath("$.reservedByMe").value(false))
                .andExpect(jsonPath("$.counterparty.phone").value("+8801722222222"));
        // A stranger after the parties: still nothing personal, i.e. nothing leaked into the cached copy.
        getListing(lamp, other).andExpect(jsonPath("$.reservedByMe").value(false))
                .andExpect(jsonPath("$.counterparty").doesNotExist())
                .andExpect(jsonPath("$.reservationExpiresAt").doesNotExist());
        String anonymous = getListing(lamp, null).andReturn().getResponse().getContentAsString();
        assertThat(anonymous).doesNotContain("+8801711111111").doesNotContain("+8801722222222").doesNotContain("Bob Buyer");

        // The public copy was read from the database once; the rest were cache hits or the party overlay.
        verify(listingRepository, times(1)).findWithOwnerById(lamp.getId());
        // The cached object itself carries no personal data.
        Object cached = cacheManager.getCache(CacheNames.LISTING_BY_ID).get(lamp.getId()).get();
        assertThat(cached.toString()).doesNotContain("+88017").contains("reservedByMe=false");
    }

    @Test
    void listingsNeitherReservedNorSold_needNoExtraQueryForALoggedInViewer() throws Exception {
        getListing(lamp, other).andExpect(status().isOk());
        getListing(lamp, other).andExpect(status().isOk());
        getListing(lamp, seller).andExpect(status().isOk());

        verify(listingRepository, times(1)).findWithOwnerById(lamp.getId());
        verify(listingRepository, times(0)).findWithPartiesById(anyLong());
    }

    @Test
    void adminsBypassTheCache_andStillSeeListingsOfDeactivatedOwners() throws Exception {
        getListing(lamp, null).andExpect(status().isOk());
        mockMvc.perform(as(patch("/api/admin/users/{id}/deactivate", seller.getId()), admin)).andExpect(status().isOk());

        getListing(lamp, null).andExpect(status().isNotFound());   // the cached copy is gone
        getListing(lamp, admin).andExpect(status().isOk());        // admins moderate it
    }

    // ---- featured row and category counts ----

    @Test
    void featuredRow_isCached() throws Exception {
        completePayment(checkoutSession(seller, "FEATURE_LISTING"), "pi_1");
        featured().andExpect(jsonPath("$.length()").value(1));
        clearInvocations(listingRepository);

        featured().andExpect(jsonPath("$.length()").value(1));
        featured().andExpect(jsonPath("$.length()").value(1));

        verify(listingRepository, times(0)).findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class));
        assertThat(hits(CacheNames.FEATURED_LISTINGS)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void categoryCounts_areCached_andEvictedByCreateMarkSoldAndDelete() throws Exception {
        counts().andExpect(jsonPath("$.OTHER").value(1)).andExpect(jsonPath("$.BOOKS").value(0));
        clearInvocations(listingRepository);
        counts().andExpect(jsonPath("$.OTHER").value(1));
        verify(listingRepository, times(0)).countActiveByCategory();

        mockMvc.perform(as(post("/api/listings"), seller).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Calculus\",\"price\":20,\"category\":\"BOOKS\"}"))
                .andExpect(status().isCreated());
        counts().andExpect(jsonPath("$.BOOKS").value(1));

        mockMvc.perform(as(patch("/api/listings/{id}/sold", lamp.getId()), seller)).andExpect(status().isOk());
        counts().andExpect(jsonPath("$.OTHER").value(0));
    }

    // ---- userProfile ----

    @Test
    void profileReads_areCached_andAnEditReplacesTheCachedCopy() throws Exception {
        me(buyer).andExpect(jsonPath("$.fullName").value("Bob Buyer"));
        me(buyer).andExpect(jsonPath("$.fullName").value("Bob Buyer"));
        assertThat(hits(CacheNames.USER_PROFILE)).isEqualTo(1);
        assertThat(misses(CacheNames.USER_PROFILE)).isEqualTo(1);

        mockMvc.perform(as(patch("/api/users/me"), buyer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Robert Buyer\",\"phone\":\"01744444444\"}"))
                .andExpect(status().isOk());

        me(buyer).andExpect(jsonPath("$.fullName").value("Robert Buyer")).andExpect(jsonPath("$.phone").value("+8801744444444"));
        me(other).andExpect(jsonPath("$.fullName").value("Olga Other")); // another user's entry is untouched
    }

    @Test
    void renamingASeller_refreshesTheirCachedListings() throws Exception {
        getListing(lamp, null).andExpect(jsonPath("$.owner.fullName").value("Sally Seller"));

        mockMvc.perform(as(patch("/api/users/me"), seller).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Sally S.\"}"))
                .andExpect(status().isOk());

        getListing(lamp, null).andExpect(jsonPath("$.owner.fullName").value("Sally S."));
    }

    @Test
    void deactivatingAUser_clearsTheirProfileEntry() throws Exception {
        me(buyer).andExpect(status().isOk());
        assertThat(cacheManager.getCache(CacheNames.USER_PROFILE).get(buyer.getId())).isNotNull();

        mockMvc.perform(as(patch("/api/admin/users/{id}/deactivate", buyer.getId()), admin)).andExpect(status().isOk());

        assertThat(cacheManager.getCache(CacheNames.USER_PROFILE).get(buyer.getId())).isNull();
    }

    // ---- feeQuote ----

    @Test
    void feeQuotes_areCached() throws Exception {
        fees(buyer).andExpect(status().isOk());
        fees(buyer).andExpect(status().isOk());
        fees(other).andExpect(status().isOk());
        assertThat(misses(CacheNames.FEE_QUOTE)).isEqualTo(1);
        assertThat(hits(CacheNames.FEE_QUOTE)).isEqualTo(2);
    }

    // ---- not cached ----

    @Test
    void searchPages_areNeverCached() throws Exception {
        mockMvc.perform(get("/api/listings").param("keyword", "lamp")).andExpect(status().isOk());
        mockMvc.perform(get("/api/listings").param("keyword", "lamp")).andExpect(status().isOk());

        verify(listingRepository, times(2)).findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class));
    }

    // ---- evictions wait for the commit ----

    @Test
    void evictionInsideATransaction_waitsForTheCommit_soARollbackLeavesTheCacheAlone() throws Exception {
        getListing(lamp, null).andExpect(status().isOk());
        Cache cache = cacheManager.getCache(CacheNames.LISTING_BY_ID);
        assertThat(cache.get(lamp.getId())).isNotNull();

        transactions.execute(tx -> {
            evictor.evictListing(lamp.getId());
            assertThat(cache.get(lamp.getId())).as("not evicted before the commit").isNotNull();
            tx.setRollbackOnly();
            return null;
        });
        assertThat(cache.get(lamp.getId())).as("a rollback must not evict").isNotNull();

        transactions.execute(tx -> {
            evictor.evictListing(lamp.getId());
            assertThat(cache.get(lamp.getId())).isNotNull();
            return null;
        });
        assertThat(cache.get(lamp.getId())).as("evicted after the commit").isNull();
    }

    // ---- helpers ----

    @Autowired private com.campus.marketplace.service.ReservationService reservationService;

    private void evictAndSweep() {
        reservationService.releaseExpired();
    }

    private ResultActions getListing(Listing listing, User viewer) throws Exception {
        return getListing(listing.getId(), viewer);
    }

    private ResultActions getListing(Long id, User viewer) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/listings/{id}", id);
        return mockMvc.perform(viewer == null ? request : as(request, viewer));
    }

    private ResultActions featured() throws Exception {
        return mockMvc.perform(get("/api/listings/featured")).andExpect(status().isOk());
    }

    private ResultActions counts() throws Exception {
        return mockMvc.perform(get("/api/listings/category-counts")).andExpect(status().isOk());
    }

    private ResultActions fees(User user) throws Exception {
        return mockMvc.perform(as(get("/api/listings/{id}/fees", lamp.getId()), user));
    }

    private ResultActions me(User user) throws Exception {
        return mockMvc.perform(as(get("/api/users/me"), user));
    }

    private String checkoutSession(User user, String purpose) throws Exception {
        ResultActions result = mockMvc.perform(as(post("/api/payments/checkout-session"), user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listingId\":" + lamp.getId() + ",\"purpose\":\"" + purpose + "\"}"))
                .andExpect(status().isCreated());
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("sessionId").asText();
    }

    private void completePayment(String sessionId, String paymentIntentId) throws Exception {
        String payload = """
                {"id":"evt_%s","object":"event","api_version":"%s","created":%d,"livemode":false,
                 "pending_webhooks":1,"request":{"id":null,"idempotency_key":null},"type":"checkout.session.completed",
                 "data":{"object":{"id":"%s","object":"checkout.session","mode":"payment",
                                   "payment_status":"paid","status":"complete","payment_intent":"%s"}}}"""
                .formatted(sessionId, Stripe.API_VERSION, Instant.now().getEpochSecond(), sessionId, paymentIntentId);
        mockMvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", Webhook.Signature.generateSignatureHeader(payload, SECRET))
                        .content(payload))
                .andExpect(status().isOk());
    }

    // Caffeine's statistics survive clear() and the caches are shared by every test in this context, so count
    // from where this test started.
    private final java.util.Map<String, long[]> baseline = new java.util.HashMap<>();

    private void snapshotStats() {
        for (String name : cacheManager.getCacheNames()) {
            var stats = ((CaffeineCache) cacheManager.getCache(name)).getNativeCache().stats();
            baseline.put(name, new long[] {stats.hitCount(), stats.missCount()});
        }
    }

    private long hits(String cache) {
        return ((CaffeineCache) cacheManager.getCache(cache)).getNativeCache().stats().hitCount() - baseline.get(cache)[0];
    }

    private long misses(String cache) {
        return ((CaffeineCache) cacheManager.getCache(cache)).getNativeCache().stats().missCount() - baseline.get(cache)[1];
    }

    private void clearCaches() {
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, User user) {
        return request.header("Authorization", "Bearer " + jwtService.generateAccessToken(UserPrincipal.from(user)));
    }

    private User saveUser(String email, String name, String phone, Role role) {
        return userRepository.save(User.builder().fullName(name).email(email).phone(phone).password("x")
                .role(role).enabled(true).build());
    }
}
