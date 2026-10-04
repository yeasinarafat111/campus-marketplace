package com.campus.marketplace.service;

import com.campus.marketplace.config.PaymentProperties;
import com.campus.marketplace.dto.CheckoutSessionRequest;
import com.campus.marketplace.dto.CheckoutSessionResponse;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.Payment;
import com.campus.marketplace.entity.PaymentPurpose;
import com.campus.marketplace.entity.PaymentStatus;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.exception.BadRequestException;
import com.campus.marketplace.exception.ConflictException;
import com.campus.marketplace.exception.ForbiddenException;
import com.campus.marketplace.exception.ResourceNotFoundException;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.PaymentRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final ListingRepository listingRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUser;
    private final CheckoutGateway checkoutGateway;
    private final PaymentProperties paymentProperties;

    /**
     * Creates a Stripe Checkout Session for a fixed, server-side amount and records a PENDING payment.
     * <p>
     * Stripe is called before anything is saved and outside a transaction, so a slow Stripe call doesn't hold
     * a database connection. If saving then fails, the unused session simply expires; nothing is charged
     * until the user completes checkout.
     */
    public CheckoutSessionResponse createCheckoutSession(CheckoutSessionRequest request) {
        Long userId = currentUser.requireId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Listing listing = listingRepository.findWithOwnerById(request.listingId())
                .filter(l -> l.getOwner().isActive())
                .orElseThrow(() -> new ResourceNotFoundException("Listing " + request.listingId() + " not found"));

        if (listing.getStatus() != ListingStatus.ACTIVE) {
            throw new ConflictException("This listing is no longer available");
        }
        boolean isOwner = Objects.equals(listing.getOwner().getId(), userId);
        if (request.purpose() == PaymentPurpose.FEATURE_LISTING && !isOwner) {
            throw new ForbiddenException("Only the listing owner can feature a listing");
        }
        if (request.purpose() == PaymentPurpose.RESERVE_ITEM && isOwner) {
            throw new BadRequestException("You can't reserve your own listing");
        }
        // Refuse up front rather than take a second payment the webhook would then not apply.
        if (request.purpose() == PaymentPurpose.RESERVE_ITEM && listing.isReserved()) {
            throw new ConflictException("This listing is already reserved");
        }
        if (request.purpose() == PaymentPurpose.FEATURE_LISTING && listing.isFeatured(Instant.now())) {
            throw new ConflictException("This listing is already featured");
        }

        long amount = paymentProperties.amountFor(request.purpose());
        String currency = paymentProperties.currency();
        var session = checkoutGateway.createCheckoutSession(new CheckoutGateway.CheckoutRequest(
                amount,
                currency,
                productName(request.purpose(), listing),
                user.getEmail(),
                Map.of(
                        "user_id", userId.toString(),
                        "listing_id", listing.getId().toString(),
                        "purpose", request.purpose().name())));

        Payment payment = paymentRepository.save(Payment.builder()
                .user(user)
                .listing(listing)
                .amount(amount)
                .currency(currency)
                .purpose(request.purpose())
                .status(PaymentStatus.PENDING)
                .stripeSessionId(session.id())
                .build());

        return new CheckoutSessionResponse(payment.getId(), session.id(), session.url(), amount, currency,
                payment.getPurpose(), payment.getStatus());
    }

    /**
     * Records a completed payment and applies its effect once. Idempotent:
     * <ul>
     *   <li>a redelivered event for an already-SUCCEEDED payment changes nothing;</li>
     *   <li>if the listing is already featured / already reserved (e.g. two checkouts raced), the payment is
     *       still marked SUCCEEDED, so the money is on record, but the effect is not applied again;</li>
     *   <li>if the listing was deleted in the meantime, only the payment is updated.</li>
     * </ul>
     * Payment and listing rows are locked for the duration, so concurrent deliveries can't double-apply.
     */
    @Transactional
    public void markSucceeded(String stripeSessionId) {
        Payment payment = paymentRepository.findForUpdateByStripeSessionId(stripeSessionId).orElse(null);
        if (payment == null) {
            log.warn("Stripe webhook for unknown checkout session {}; ignoring", stripeSessionId);
            return;
        }
        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
            log.info("Payment {} already SUCCEEDED; duplicate webhook ignored", payment.getId());
            return;
        }
        payment.setStatus(PaymentStatus.SUCCEEDED);

        Listing listing = payment.getListing() == null ? null
                : listingRepository.findForUpdateById(payment.getListing().getId()).orElse(null);
        if (listing == null) {
            log.warn("Payment {} succeeded but its listing no longer exists; nothing to apply", payment.getId());
            return;
        }

        Instant now = Instant.now();
        switch (payment.getPurpose()) {
            case FEATURE_LISTING -> {
                if (listing.isFeatured(now)) {
                    log.warn("Payment {} succeeded but listing {} is already featured until {}; not extended",
                            payment.getId(), listing.getId(), listing.getFeaturedUntil());
                } else {
                    listing.setFeaturedUntil(now.plus(paymentProperties.featureDuration()));
                    log.info("Listing {} featured until {} (payment {})", listing.getId(), listing.getFeaturedUntil(), payment.getId());
                }
            }
            case RESERVE_ITEM -> {
                if (listing.isReserved()) {
                    log.warn("Payment {} succeeded but listing {} is already reserved; deposit needs refunding",
                            payment.getId(), listing.getId());
                } else {
                    listing.setReservedBy(payment.getUser());
                    listing.setReservedAt(now);
                    log.info("Listing {} reserved by user {} (payment {})", listing.getId(), payment.getUser().getId(), payment.getId());
                }
            }
        }
    }

    /** Marks a PENDING payment FAILED (expired or failed async payment). Never downgrades a SUCCEEDED one. */
    @Transactional
    public void markFailed(String stripeSessionId) {
        paymentRepository.findForUpdateByStripeSessionId(stripeSessionId).ifPresentOrElse(payment -> {
            if (payment.getStatus() == PaymentStatus.PENDING) {
                payment.setStatus(PaymentStatus.FAILED);
                log.info("Payment {} marked FAILED", payment.getId());
            }
        }, () -> log.warn("Stripe webhook for unknown checkout session {}; ignoring", stripeSessionId));
    }

    private static String productName(PaymentPurpose purpose, Listing listing) {
        String title = listing.getTitle().length() > 80 ? listing.getTitle().substring(0, 80) + "…" : listing.getTitle();
        return switch (purpose) {
            case FEATURE_LISTING -> "Feature listing: " + title;
            case RESERVE_ITEM -> "Reservation deposit: " + title;
        };
    }
}
