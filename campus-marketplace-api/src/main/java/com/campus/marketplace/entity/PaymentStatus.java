package com.campus.marketplace.entity;

public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    /** The money was returned to the payer through Stripe (a cancelled reservation or a duplicate deposit). */
    REFUNDED
}
