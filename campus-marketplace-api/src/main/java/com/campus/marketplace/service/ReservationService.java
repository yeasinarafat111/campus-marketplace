package com.campus.marketplace.service;

import com.campus.marketplace.config.CacheNames;
import com.campus.marketplace.config.PaymentProperties;
import com.campus.marketplace.dto.ListingResponse;
import com.campus.marketplace.dto.OrderResponse;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.Order;
import com.campus.marketplace.entity.OrderStatus;
import com.campus.marketplace.entity.PaymentMethod;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.exception.ConflictException;
import com.campus.marketplace.exception.ForbiddenException;
import com.campus.marketplace.exception.ResourceNotFoundException;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.OrderRepository;
import com.campus.marketplace.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Ending a reservation: the seller cancelling it (full refund, decision D4), the buyer releasing it (deposit
 * kept, D5) and time running out (deposit kept, D5). Buying is a payment and lives in {@link PaymentService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ListingRepository listingRepository;
    private final PaymentService paymentService;
    private final CurrentUserProvider currentUser;
    private final PaymentProperties paymentProperties;
    private final ListingCacheEvictor cacheEvictor;
    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher events;

    /**
     * Seller only. Refunds the buyer's deposit through Stripe, then frees the item. The listing row is locked
     * and the refund happens before anything is changed: if Stripe fails the whole call rolls back (502) and
     * nothing is lost; if the commit then fails, a retry sends the same idempotency key, so Stripe returns the
     * original refund instead of paying twice.
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.LISTING_BY_ID, key = "#listingId"),
            @CacheEvict(cacheNames = CacheNames.FEATURED_LISTINGS, allEntries = true)})
    @Transactional
    public ListingResponse cancelAsSeller(Long listingId) {
        Listing listing = lock(listingId);
        Long userId = currentUser.requireId();
        if (!Objects.equals(listing.getOwner().getId(), userId)) {
            throw new ForbiddenException("Only the seller can cancel a reservation");
        }
        if (!listing.isReserved()) {
            throw new ConflictException("This listing isn't reserved");
        }
        paymentService.refundDeposit(listing);
        listing.clearReservation();
        log.info("Seller {} cancelled the reservation on listing {}; deposit refunded", userId, listingId);
        return ListingResponse.from(listing, userId);
    }

    /**
     * The reserver chooses to pay the balance in cash at the meetup instead of online. Nothing is charged; it
     * only records the choice so the seller knows to expect cash. The reservation (and its expiry) carries on as
     * before. Idempotent. The reserver can still pay online instead.
     */
    @Transactional
    public ListingResponse chooseCash(Long listingId) {
        Listing listing = lock(listingId);
        Long userId = currentUser.requireId();
        requireLiveReservation(listing);
        if (!Objects.equals(listing.getReservedBy().getId(), userId)) {
            throw new ForbiddenException("Only the student who reserved this item can choose how to pay");
        }
        if (listing.getCashChosenAt() == null) {
            listing.setCashChosenAt(Instant.now());
            log.info("Buyer {} chose to pay cash for listing {}", userId, listingId);
        }
        return withBalance(ListingResponse.from(listing, userId), listing);
    }

    /**
     * Seller only: "Confirm handover and cash received". Sells the item to the reserver: the listing becomes SOLD
     * and a CASH order is recorded as COMPLETED at once, with the deposit as the only money the platform holds.
     * Allowed only while the reservation is live (an expired one can't be confirmed, even before the sweep has
     * freed it), after the buyer chose cash, and once: the item is sold afterwards, so a second call is a 409.
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.LISTING_BY_ID, key = "#listingId"),
            @CacheEvict(cacheNames = CacheNames.FEE_QUOTE, key = "#listingId"),
            @CacheEvict(cacheNames = CacheNames.FEATURED_LISTINGS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CATEGORY_COUNTS, allEntries = true)})
    @Transactional
    public OrderResponse confirmCashHandover(Long listingId) {
        Listing listing = lock(listingId);
        Long userId = currentUser.requireId();
        if (!Objects.equals(listing.getOwner().getId(), userId)) {
            throw new ForbiddenException("Only the seller can confirm the handover");
        }
        requireLiveReservation(listing);
        if (listing.getCashChosenAt() == null) {
            throw new ConflictException("The buyer hasn't chosen to pay cash");
        }
        User buyer = listing.getReservedBy();
        long priceCents = FeeCalculator.toCents(listing.getPrice());
        Long balanceDue = paymentService.balanceDue(listing);
        long deposit = balanceDue == null ? 0L : priceCents - balanceDue;
        String currency = paymentProperties.currency();

        listing.setStatus(ListingStatus.SOLD);
        listing.setBuyer(buyer);
        listing.setSoldAt(Instant.now());
        listing.clearReservation();

        Order order = orderRepository.save(Order.builder()
                .listing(listing)
                .listingTitle(listing.getTitle())
                .listingImageUrl(listing.getImageUrl())
                .buyer(buyer)
                .seller(listing.getOwner())
                .itemPriceCents(priceCents)
                .depositCents(deposit)
                .balanceCents(priceCents - deposit)
                .currency(currency)
                .paymentMethod(PaymentMethod.CASH)
                .meetupSpot(listing.getMeetupSpot())
                .status(OrderStatus.COMPLETED)
                .completedAt(Instant.now())
                .build());
        log.info("Seller {} confirmed the cash handover of listing {} to buyer {}", userId, listingId, buyer.getId());
        events.publishEvent(new TradeEvents.CashSaleCompleted(listingId, listing.getTitle(), priceCents, deposit,
                priceCents - deposit, currency, party(listing.getOwner()), party(buyer)));
        return OrderResponse.forSeller(order, Duration.ZERO);
    }

    /** The buyer gives the item up. The deposit is not refunded (decision D5); the UI says so beforehand. */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.LISTING_BY_ID, key = "#listingId"),
            @CacheEvict(cacheNames = CacheNames.FEATURED_LISTINGS, allEntries = true)})
    @Transactional
    public ListingResponse releaseAsBuyer(Long listingId) {
        Listing listing = lock(listingId);
        Long userId = currentUser.requireId();
        if (!listing.isReserved()) {
            throw new ConflictException("This listing isn't reserved");
        }
        if (!Objects.equals(listing.getReservedBy().getId(), userId)) {
            throw new ForbiddenException("Only the student who reserved this item can release it");
        }
        listing.clearReservation();
        log.info("Buyer {} released the reservation on listing {}; deposit kept", userId, listingId);
        return ListingResponse.from(listing, userId);
    }

    /**
     * Frees every reservation whose time is up, without refunding. Safe to run on several instances at once
     * (see {@link ListingRepository#releaseExpiredReservations}). Reservations that predate expiry have no end
     * time; they get one full window from now first, so they are never expired retroactively.
     *
     * @return how many reservations were released
     */
    @Transactional
    public int releaseExpired() {
        Instant now = Instant.now();
        int backfilled = listingRepository.backfillReservationExpiry(now.plus(paymentProperties.reservationDuration()));
        if (backfilled > 0) {
            log.info("Gave {} older reservation(s) an expiry of {}", backfilled, paymentProperties.reservationDuration());
        }
        int released = listingRepository.releaseExpiredReservations(now);
        if (released > 0) {
            log.info("Released {} expired reservation(s)", released);
            cacheEvictor.evictAllListings();
        }
        return released;
    }

    /** 409 unless the listing is reserved and that reservation hasn't run out (even if the sweep hasn't freed it yet). */
    private void requireLiveReservation(Listing listing) {
        if (!listing.isReserved() || listing.getStatus() != ListingStatus.ACTIVE) {
            throw new ConflictException("This listing isn't reserved");
        }
        Instant expiresAt = listing.getReservationExpiresAt();
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new ConflictException("This reservation has expired");
        }
    }

    private ListingResponse withBalance(ListingResponse response, Listing listing) {
        return response.withBalanceDue(paymentService.balanceDue(listing));
    }

    private static TradeEvents.Party party(User user) {
        return new TradeEvents.Party(user.getFullName(), user.getEmail(), user.getPhone());
    }

    private Listing lock(Long listingId) {
        return listingRepository.findForUpdateById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("Listing " + listingId + " not found"));
    }
}
