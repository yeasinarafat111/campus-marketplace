package com.campus.marketplace.entity;

public enum OrderStatus {
    /** The buyer has paid in full (deposit plus balance). The seller is owed the money. */
    PAID,
    /** The seller has been paid out. */
    COMPLETED,
    CANCELLED
}
