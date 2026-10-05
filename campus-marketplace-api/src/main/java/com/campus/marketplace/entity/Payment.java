package com.campus.marketplace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/**
 * A Stripe Checkout payment. Payments are financial records, so they are never cascade-deleted:
 * deleting a listing keeps the payment (listing becomes null), and a user with payments can't be
 * hard-deleted (users are deactivated instead).
 */
@Entity
@Table(name = "payments", indexes = {
        @Index(name = "idx_payments_user", columnList = "user_id"),
        @Index(name = "idx_payments_listing", columnList = "listing_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"user", "listing"})
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "listing_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Listing listing;

    /** In the currency's smallest unit (e.g. cents), exactly as sent to Stripe. */
    @Column(nullable = false)
    private Long amount;

    /** ISO 4217, lower-case (Stripe's format), e.g. "usd". */
    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(nullable = false, unique = true)
    private String stripeSessionId;

    /**
     * The Stripe PaymentIntent behind the checkout session, needed to refund. Set when the payment succeeds;
     * null on payments made before refunds existed (looked up from the session when needed).
     */
    @Column(length = 255)
    private String stripePaymentIntentId;

    /**
     * Audit trail for the fee: the listing price (in minor units) and the nominal rate (in basis points,
     * 300 = 3%) the amount was computed from. Null on payments made before fees were price-based.
     */
    private Long basisPriceCents;

    private Integer rateBasisPoints;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
