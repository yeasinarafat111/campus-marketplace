package com.campus.marketplace.entity;

public enum PaymentPurpose {
    /** Owner pays a small fee to promote their listing. */
    FEATURE_LISTING,
    /** Buyer pays a deposit to hold an item. */
    RESERVE_ITEM,
    /** The reserver pays the rest of the price (price minus the deposit) to buy the item. */
    PURCHASE
}
