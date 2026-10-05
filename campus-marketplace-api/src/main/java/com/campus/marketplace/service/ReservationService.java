package com.campus.marketplace.service;

import com.campus.marketplace.config.CacheNames;
import com.campus.marketplace.config.PaymentProperties;
import com.campus.marketplace.dto.ListingResponse;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.exception.ConflictException;
import com.campus.marketplace.exception.ForbiddenException;
import com.campus.marketplace.exception.ResourceNotFoundException;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private Listing lock(Long listingId) {
        return listingRepository.findForUpdateById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("Listing " + listingId + " not found"));
    }
}
