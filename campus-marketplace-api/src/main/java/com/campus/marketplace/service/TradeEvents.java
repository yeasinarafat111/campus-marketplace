package com.campus.marketplace.service;

import java.time.Instant;

/**
 * Facts about a committed reservation or purchase, published from inside the payment transaction and turned
 * into emails after it commits. They carry plain values (no entities), so the listener needs no database.
 */
public final class TradeEvents {

    private TradeEvents() {
    }

    /** One side of a trade. {@code phone} is null for accounts that haven't added one. */
    public record Party(String name, String email, String phone) {
    }

    public record ReservationConfirmed(Long listingId, String title, long priceCents, long depositCents,
                                       String currency, Party seller, Party buyer, Instant expiresAt) {
    }

    /** The seller confirmed a cash handover: the buyer paid the balance in person. */
    public record CashSaleCompleted(Long listingId, String title, long priceCents, long depositCents,
                                    long cashCents, String currency, Party seller, Party buyer) {
    }

    public record PurchaseCompleted(Long listingId, String title, long priceCents, long depositCents,
                                    long balanceCents, String currency, Party seller, Party buyer) {
    }
}
