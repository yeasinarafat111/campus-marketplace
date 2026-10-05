package com.campus.marketplace.entity;

/** Whether the platform has handed the seller their money yet. Set by an admin (real payouts come later). */
public enum PayoutStatus {
    PENDING,
    PAID_OUT
}
