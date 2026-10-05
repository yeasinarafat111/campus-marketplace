package com.campus.marketplace.service;

import com.campus.marketplace.config.PaymentProperties;
import com.campus.marketplace.dto.CheckoutSessionRequest;
import com.campus.marketplace.dto.CheckoutSessionResponse;
import com.campus.marketplace.dto.FeeQuote;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.Order;
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
import com.campus.marketplace.repository.OrderRepository;
import com.campus.marketplace.repository.PaymentRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    /** Decision D7: paid listings cost at least this much. */
    static final BigDecimal MIN_PAID_PRICE = new BigDecimal("1.00");

    private final PaymentRepository paymentRepository;
    private final ListingRepository listingRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUser;
    private final CheckoutGateway checkoutGateway;
    private final PaymentProperties paymentProperties;
    private final FeeCalculator feeCalculator;
    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher events;

    /**
     * The fees a signed-in user would pay for this listing right now, for the buttons on the listing page.
     * Read-only; nothing is reserved or charged.
     */
    @Transactional(readOnly = true)
    public FeeQuote quote(Long listingId) {
        Listing listing = listingRepository.findWithOwnerById(listingId)
                .filter(l -> l.getOwner().isActive())
                .orElseThrow(() -> new ResourceNotFoundException("Listing " + listingId + " not found"));
        if (listing.getStatus() != ListingStatus.ACTIVE) {
            throw new ConflictException("This listing is no longer available");
        }
        requireChargeable(listing);
        BigDecimal price = listing.getPrice();
        long feeCents = feeCalculator.feeCents(price);
        return new FeeQuote(
                feeCents,
                feeCents,
                FeeCalculator.toCents(price) - feeCents,
                paymentProperties.currency(),
                feeCalculator.rateFor(price),
                paymentProperties.fee().minimumCents(),
                price.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                paymentProperties.featureDuration().toDays(),
                paymentProperties.reservationDuration().toHours());
    }

    /**
     * Free items (decision D7) have no paid flows, and the balance after the deposit must itself be a
     * chargeable amount, which holds from the minimum paid price up.
     */
    private static void requireChargeable(Listing listing) {
        BigDecimal price = listing.getPrice();
        if (price.signum() == 0) {
            throw new BadRequestException("Free items can't be featured or reserved. Message the owner instead.");
        }
        if (price.compareTo(MIN_PAID_PRICE) < 0) {
            throw new BadRequestException("Items under $1.00 can't be featured or reserved. "
                    + "Ask the owner to update the price.");
        }
    }

    /**
     * Creates a Stripe Checkout Session for a server-side amount computed from the listing price and records a PENDING payment.
     * <p>
     * Stripe is called before anything is saved and outside a transaction, so a slow Stripe call doesn't hold
     * a database connection. If saving then fails, the unused session simply expires; nothing is charged
     * until the user completes checkout.
     */
    public CheckoutSessionResponse createCheckoutSession(CheckoutSessionRequest request) {
        Long userId = currentUser.requireId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Listing listing = listingRepository.findWithPartiesById(request.listingId())
                .filter(l -> l.getOwner().isActive())
                .orElseThrow(() -> new ResourceNotFoundException("Listing " + request.listingId() + " not found"));

        if (listing.getStatus() != ListingStatus.ACTIVE) {
            throw new ConflictException("This listing is no longer available");
        }
        boolean isOwner = Objects.equals(listing.getOwner().getId(), userId);
        Charge charge = switch (request.purpose()) {
            case FEATURE_LISTING -> {
                if (!isOwner) {
                    throw new ForbiddenException("Only the listing owner can feature a listing");
                }
                if (listing.isFeatured(Instant.now())) {
                    throw new ConflictException("This listing is already featured");
                }
                yield feeCharge(listing);
            }
            case RESERVE_ITEM -> {
                if (isOwner) {
                    throw new BadRequestException("You can't reserve your own listing");
                }
                // Refuse up front rather than take a second payment the webhook would then have to refund.
                if (listing.isReserved()) {
                    throw new ConflictException("This listing is already reserved");
                }
                yield feeCharge(listing);
            }
            case PURCHASE -> purchaseCharge(listing, userId, isOwner);
        };

        String currency = paymentProperties.currency();
        var session = checkoutGateway.createCheckoutSession(new CheckoutGateway.CheckoutRequest(
                charge.amount(),
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
                .amount(charge.amount())
                .currency(currency)
                .purpose(request.purpose())
                .status(PaymentStatus.PENDING)
                .stripeSessionId(session.id())
                .basisPriceCents(charge.basisPriceCents())
                .rateBasisPoints(charge.rateBasisPoints())
                .build());

        return new CheckoutSessionResponse(payment.getId(), session.id(), session.url(), charge.amount(), currency,
                payment.getPurpose(), payment.getStatus());
    }

    /** What to charge and the numbers it was worked out from, for the audit fields on the payment. */
    private record Charge(long amount, long basisPriceCents, Integer rateBasisPoints) {
    }

    /** Feature and Reserve: a fee computed from the price (decision D1). */
    private Charge feeCharge(Listing listing) {
        requireChargeable(listing);
        BigDecimal price = listing.getPrice();
        return new Charge(feeCalculator.feeCents(price), FeeCalculator.toCents(price),
                feeCalculator.rateFor(price).movePointRight(4).intValueExact());
    }

    /**
     * Buy: only the student holding a live reservation, paying the price minus the deposit they already paid
     * (decision D2). The amount comes from our records of that deposit, never from the client.
     */
    private Charge purchaseCharge(Listing listing, Long userId, boolean isOwner) {
        if (isOwner) {
            throw new BadRequestException("You can't buy your own listing");
        }
        if (!listing.isReserved()) {
            throw new ConflictException("Reserve this item before you buy it");
        }
        if (!Objects.equals(listing.getReservedBy().getId(), userId)) {
            throw new ForbiddenException("Only the student who reserved this item can buy it");
        }
        Instant expiresAt = listing.getReservationExpiresAt();
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new ConflictException("Your reservation has expired");
        }
        Payment deposit = paymentRepository.findFirstByListingIdAndUserIdAndPurposeAndStatusOrderByIdDesc(
                        listing.getId(), userId, PaymentPurpose.RESERVE_ITEM, PaymentStatus.SUCCEEDED)
                .orElseThrow(() -> new ConflictException("We couldn't find your reservation deposit"));
        long priceCents = FeeCalculator.toCents(listing.getPrice());
        long balance = priceCents - deposit.getAmount();
        long minimum = "usd".equals(paymentProperties.currency()) ? PaymentProperties.STRIPE_MINIMUM_USD_CENTS : 1;
        if (balance < minimum) {
            throw new ConflictException("The balance is too small to pay online. Please contact support.");
        }
        return new Charge(balance, priceCents, null);
    }

    /**
     * Records a completed payment and applies its effect once. Idempotent:
     * <ul>
     *   <li>a redelivered event for an already-SUCCEEDED (or REFUNDED) payment changes nothing;</li>
     *   <li>a feature payment for a listing that is already featured is recorded but not extended;</li>
     *   <li>a deposit or purchase that can't be applied (the item was reserved by someone else meanwhile, was
     *       sold, or was deleted) is refunded through Stripe automatically.</li>
     * </ul>
     * Payment and listing rows are locked for the duration, so concurrent deliveries can't double-apply.
     * If a needed refund fails, the exception rolls everything back and Stripe retries the webhook later.
     *
     * @param paymentIntentId the session's PaymentIntent, kept so the payment can be refunded; may be null
     */
    @Transactional
    public void markSucceeded(String stripeSessionId, String paymentIntentId) {
        Payment payment = paymentRepository.findForUpdateByStripeSessionId(stripeSessionId).orElse(null);
        if (payment == null) {
            log.warn("Stripe webhook for unknown checkout session {}; ignoring", stripeSessionId);
            return;
        }
        if (payment.getStatus() == PaymentStatus.SUCCEEDED || payment.getStatus() == PaymentStatus.REFUNDED) {
            log.info("Payment {} already {}; duplicate webhook ignored", payment.getId(), payment.getStatus());
            return;
        }
        payment.setStatus(PaymentStatus.SUCCEEDED);
        if (paymentIntentId != null) {
            payment.setStripePaymentIntentId(paymentIntentId);
        }

        Listing listing = payment.getListing() == null ? null
                : listingRepository.findForUpdateById(payment.getListing().getId()).orElse(null);
        if (listing == null) {
            log.warn("Payment {} succeeded but its listing no longer exists", payment.getId());
            if (payment.getPurpose() != PaymentPurpose.FEATURE_LISTING) {
                refund(payment);
            }
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
            case RESERVE_ITEM -> applyReservation(payment, listing, now);
            case PURCHASE -> applyPurchase(payment, listing, now);
        }
    }

    private void applyReservation(Payment payment, Listing listing, Instant now) {
        if (listing.getStatus() != ListingStatus.ACTIVE || listing.isReserved()) {
            log.warn("Payment {} succeeded but listing {} is {}; refunding the deposit", payment.getId(),
                    listing.getId(), listing.isReserved() ? "already reserved" : "no longer available");
            refund(payment);
            return;
        }
        User buyer = payment.getUser();
        listing.setReservedBy(buyer);
        listing.setReservedAt(now);
        listing.setReservationExpiresAt(now.plus(paymentProperties.reservationDuration()));
        log.info("Listing {} reserved by user {} until {} (payment {})", listing.getId(), buyer.getId(),
                listing.getReservationExpiresAt(), payment.getId());
        events.publishEvent(new TradeEvents.ReservationConfirmed(
                listing.getId(), listing.getTitle(), FeeCalculator.toCents(listing.getPrice()), payment.getAmount(),
                payment.getCurrency(), party(listing.getOwner()), party(buyer), listing.getReservationExpiresAt()));
    }

    /**
     * Completes the sale. It applies when the listing is still for sale and is either held by this buyer or
     * free again (their reservation lapsed while they were paying: they have paid deposit plus balance, so
     * the sale stands). It does not apply, and the balance is refunded, if someone else holds the item or it
     * is already sold.
     */
    private void applyPurchase(Payment payment, Listing listing, Instant now) {
        User buyer = payment.getUser();
        boolean heldByOther = listing.isReserved() && !Objects.equals(listing.getReservedBy().getId(), buyer.getId());
        if (listing.getStatus() != ListingStatus.ACTIVE || heldByOther) {
            log.warn("Payment {} succeeded but listing {} can't be sold to user {}; refunding the balance",
                    payment.getId(), listing.getId(), buyer.getId());
            refund(payment);
            return;
        }
        long deposit = paymentRepository.findFirstByListingIdAndUserIdAndPurposeAndStatusOrderByIdDesc(
                        listing.getId(), buyer.getId(), PaymentPurpose.RESERVE_ITEM, PaymentStatus.SUCCEEDED)
                .map(Payment::getAmount).orElse(0L);

        listing.setStatus(ListingStatus.SOLD);
        listing.setBuyer(buyer);
        listing.setSoldAt(now);
        listing.clearReservation();

        long priceCents = FeeCalculator.toCents(listing.getPrice());
        orderRepository.save(Order.builder()
                .listing(listing)
                .listingTitle(listing.getTitle())
                .listingImageUrl(listing.getImageUrl())
                .buyer(buyer)
                .seller(listing.getOwner())
                .itemPriceCents(priceCents)
                .depositCents(deposit)
                .balanceCents(payment.getAmount())
                .currency(payment.getCurrency())
                .payment(payment)
                .build());
        log.info("Listing {} sold to user {} (payment {})", listing.getId(), buyer.getId(), payment.getId());
        events.publishEvent(new TradeEvents.PurchaseCompleted(
                listing.getId(), listing.getTitle(), priceCents, deposit, payment.getAmount(), payment.getCurrency(),
                party(listing.getOwner()), party(buyer)));
    }

    /**
     * Returns a payment's money through Stripe and records it as REFUNDED. Call inside a transaction that has
     * the payment loaded. The idempotency key makes a retry after a half-finished attempt return the same
     * refund instead of paying twice.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refund(Payment payment) {
        String paymentIntentId = payment.getStripePaymentIntentId();
        if (paymentIntentId == null) {
            // Paid before the PaymentIntent was stored: ask Stripe which one the session created.
            paymentIntentId = checkoutGateway.paymentIntentIdOf(payment.getStripeSessionId());
            payment.setStripePaymentIntentId(paymentIntentId);
        }
        checkoutGateway.refund(paymentIntentId, "refund-payment-" + payment.getId());
        payment.setStatus(PaymentStatus.REFUNDED);
        log.info("Payment {} ({} {}) refunded", payment.getId(), payment.getAmount(), payment.getCurrency());
    }

    /**
     * Refunds the deposit that is holding this listing (the reserver's newest successful RESERVE_ITEM
     * payment) and returns it, or returns null and refunds nothing if no such payment is on record.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Payment refundDeposit(Listing listing) {
        Long reserverId = listing.getReservedBy().getId();
        var deposit = paymentRepository.findFirstByListingIdAndUserIdAndPurposeAndStatusOrderByIdDesc(
                listing.getId(), reserverId, PaymentPurpose.RESERVE_ITEM, PaymentStatus.SUCCEEDED);
        if (deposit.isEmpty()) {
            log.error("Listing {} is reserved by user {} but no successful deposit is on record; nothing to refund",
                    listing.getId(), reserverId);
            return null;
        }
        refund(deposit.get());
        return deposit.get();
    }

    private static TradeEvents.Party party(User user) {
        return new TradeEvents.Party(user.getFullName(), user.getEmail(), user.getPhone());
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
            case PURCHASE -> "Purchase (balance after deposit): " + title;
        };
    }
}
