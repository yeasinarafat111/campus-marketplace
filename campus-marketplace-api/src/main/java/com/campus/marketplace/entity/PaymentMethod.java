package com.campus.marketplace.entity;

/** How the balance of an order was paid: through the platform (Stripe), or in cash at the meetup. */
public enum PaymentMethod {
    ONLINE,
    CASH
}
