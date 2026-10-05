package com.campus.marketplace.dto;

import com.campus.marketplace.entity.Order;
import com.campus.marketplace.entity.OrderStatus;
import com.campus.marketplace.entity.PayoutStatus;
import com.campus.marketplace.entity.User;

import java.time.Instant;

/** An order for the admin's payout ledger: who bought, who is owed what, and whether they have been paid. */
public record AdminOrderResponse(
        Long id,
        Long listingId,
        String listingTitle,
        long itemPriceCents,
        long depositCents,
        long balanceCents,
        String currency,
        OrderStatus status,
        PayoutStatus payoutStatus,
        Instant createdAt,
        Instant completedAt,
        Party buyer,
        Party seller
) {
    public record Party(Long id, String fullName, String email, String phone) {
        static Party of(User user) {
            return new Party(user.getId(), user.getFullName(), user.getEmail(), user.getPhone());
        }
    }

    /** Call inside a transaction. */
    public static AdminOrderResponse from(Order order) {
        return new AdminOrderResponse(
                order.getId(),
                order.getListing() == null ? null : order.getListing().getId(),
                order.getListingTitle(),
                order.getItemPriceCents(),
                order.getDepositCents(),
                order.getBalanceCents(),
                order.getCurrency(),
                order.getStatus(),
                order.getPayoutStatus(),
                order.getCreatedAt(),
                order.getCompletedAt(),
                Party.of(order.getBuyer()),
                Party.of(order.getSeller()));
    }
}
