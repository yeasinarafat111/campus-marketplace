package com.campus.marketplace.entity;

public enum NotificationType {
    /** Seller: someone reserved your item. */
    RESERVED,
    /** Buyer: your reservation ends in about six hours. */
    RESERVATION_EXPIRING,
    /** Seller: someone bought your item online. */
    PURCHASED,
    /** Buyer: the seller confirmed the cash handover. */
    CASH_HANDOVER_CONFIRMED,
    /** Reporter: an admin closed your report. */
    REPORT_RESOLVED
}
