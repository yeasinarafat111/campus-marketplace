package com.campus.marketplace.dto;

import com.campus.marketplace.entity.MeetupSpot;
import com.campus.marketplace.entity.Order;
import com.campus.marketplace.entity.OrderStatus;
import com.campus.marketplace.entity.PaymentMethod;
import com.campus.marketplace.entity.PayoutStatus;
import com.campus.marketplace.entity.User;

import java.time.Duration;
import java.time.Instant;

/**
 * An order as its own buyer or seller sees it. Money is in minor units. {@code counterparty} is the other
 * side (the seller, to the buyer; the buyer, to the seller) with the phone number, which they may see because
 * they are party to the order, and {@code meetupSpot} is where the item changes hands.
 * <p>
 * A PAID order completes when the buyer confirms receipt, or by itself at {@code autoCompleteAt} (null once
 * the order is no longer waiting, or while a problem report freezes it). {@code problemReason} is the buyer's
 * own text, so only the buyer's view carries it.
 */
public record OrderResponse(
        Long id,
        Long listingId,
        String listingTitle,
        String listingImageUrl,
        long itemPriceCents,
        long depositCents,
        long balanceCents,
        String currency,
        MeetupSpot meetupSpot,
        PaymentMethod paymentMethod,
        OrderStatus status,
        PayoutStatus payoutStatus,
        Instant createdAt,
        Instant completedAt,
        Instant autoCompleteAt,
        Instant problemReportedAt,
        String problemReason,
        ListingResponse.Contact counterparty,
        /** Buyer's view only: the order is completed and not rated yet. */
        boolean canRate,
        /** The buyer's rating of the seller, once given. Both sides see it. */
        Rating rating
) {
    public record Rating(int stars, String comment, Instant createdAt) {
    }

    /** This order with its rating attached (and so no longer rate-able). */
    public OrderResponse withRating(com.campus.marketplace.entity.Rating r) {
        return new OrderResponse(id, listingId, listingTitle, listingImageUrl, itemPriceCents, depositCents, balanceCents,
                currency, meetupSpot, paymentMethod, status, payoutStatus, createdAt, completedAt, autoCompleteAt,
                problemReportedAt, problemReason, counterparty, false, new Rating(r.getStars(), r.getComment(), r.getCreatedAt()));
    }

    /** Call inside a transaction (reads the lazy listing id, buyer and seller). */
    public static OrderResponse forBuyer(Order order, Duration autoCompleteAfter) {
        return of(order, order.getSeller(), true, autoCompleteAfter);
    }

    public static OrderResponse forSeller(Order order, Duration autoCompleteAfter) {
        return of(order, order.getBuyer(), false, autoCompleteAfter);
    }

    private static OrderResponse of(Order order, User counterparty, boolean forBuyer, Duration autoCompleteAfter) {
        boolean waiting = order.getStatus() == OrderStatus.PAID && order.getProblemReportedAt() == null;
        return new OrderResponse(
                order.getId(),
                order.getListing() == null ? null : order.getListing().getId(),
                order.getListingTitle(),
                order.getListingImageUrl(),
                order.getItemPriceCents(),
                order.getDepositCents(),
                order.getBalanceCents(),
                order.getCurrency(),
                order.getMeetupSpot(),
                order.getPaymentMethod(),
                order.getStatus(),
                order.getPayoutStatus(),
                order.getCreatedAt(),
                order.getCompletedAt(),
                waiting ? order.getCreatedAt().plus(autoCompleteAfter) : null,
                order.getProblemReportedAt(),
                forBuyer ? order.getProblemReason() : null,
                new ListingResponse.Contact(counterparty.getId(), counterparty.getFullName(), counterparty.getPhone()),
                forBuyer && order.getStatus() == OrderStatus.COMPLETED,
                null);
    }
}
