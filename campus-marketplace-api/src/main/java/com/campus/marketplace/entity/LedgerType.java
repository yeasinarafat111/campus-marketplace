package com.campus.marketplace.entity;

/**
 * What kind of platform revenue a ledger entry is. A deposit while it is held is not revenue, and neither is
 * purchase money that belongs to sellers; a deposit becomes revenue only when it is forfeited.
 */
public enum LedgerType {
    POSTING_FEE,
    FEATURE_FEE,
    /** A reservation deposit the platform keeps: the buyer released the item or the reservation expired. */
    FORFEITED_DEPOSIT
}
