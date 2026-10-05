package com.campus.marketplace.entity;

public enum ListingStatus {
    ACTIVE,
    SOLD,
    /** Created, but the posting fee hasn't been paid yet. Visible only to its owner (and admins). */
    PENDING_PAYMENT
}
