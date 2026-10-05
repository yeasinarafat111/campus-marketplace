package com.campus.marketplace.dto;

import com.campus.marketplace.entity.Order;
import com.campus.marketplace.entity.OrderStatus;
import com.campus.marketplace.entity.PayoutStatus;
import com.campus.marketplace.entity.User;

import java.time.Instant;

/**
 * An order as its own buyer or seller sees it. Money is in minor units. {@code counterparty} is the other
 * side (the seller, to the buyer; the buyer, to the seller) with the phone number, which they may see because
 * they are party to the order.
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
        OrderStatus status,
        PayoutStatus payoutStatus,
        Instant createdAt,
        Instant completedAt,
        ListingResponse.Contact counterparty
) {
    /** Call inside a transaction (reads the lazy listing id, buyer and seller). */
    public static OrderResponse forBuyer(Order order) {
        return of(order, order.getSeller());
    }

    public static OrderResponse forSeller(Order order) {
        return of(order, order.getBuyer());
    }

    private static OrderResponse of(Order order, User counterparty) {
        return new OrderResponse(
                order.getId(),
                order.getListing() == null ? null : order.getListing().getId(),
                order.getListingTitle(),
                order.getListingImageUrl(),
                order.getItemPriceCents(),
                order.getDepositCents(),
                order.getBalanceCents(),
                order.getCurrency(),
                order.getStatus(),
                order.getPayoutStatus(),
                order.getCreatedAt(),
                order.getCompletedAt(),
                new ListingResponse.Contact(counterparty.getId(), counterparty.getFullName(), counterparty.getPhone()));
    }
}
