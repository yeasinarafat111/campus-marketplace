package com.campus.marketplace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One line of the platform's revenue ledger. Append-only: money is never edited, only added, and a refund is a
 * new entry with the opposite sign (so net revenue is simply the sum). Written in the same transaction as the
 * event that earned it.
 * <p>
 * Payment, listing and user are plain ids, not foreign keys: the ledger is a financial record that must survive
 * a listing being deleted, and the title is copied for the same reason. The unique key (payment, type, amount)
 * is a safety net so the same event can't be booked twice; a reversal has the opposite amount, so it never clashes.
 */
@Entity
@Table(name = "ledger_entries",
        uniqueConstraints = @UniqueConstraint(name = "uk_ledger_payment_type_amount", columnNames = {"payment_id", "type", "amount_cents"}),
        indexes = @Index(name = "idx_ledger_occurred_at", columnList = "occurred_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LedgerType type;

    /** Minor units. Positive for revenue, negative for a refund of it. */
    @Column(nullable = false)
    private Long amountCents;

    /** ISO 4217, lower-case. */
    @Column(nullable = false, length = 3)
    private String currency;

    private Long paymentId;

    private Long listingId;

    /** Copied from the listing, which can be deleted later. */
    @Column(length = 120)
    private String listingTitle;

    /** Who paid (the seller for a fee, the buyer for a forfeited deposit). */
    private Long userId;

    @Column(nullable = false)
    private Instant occurredAt;
}
