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
import jakarta.persistence.OneToOne;
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
 * A completed sale: the buyer paid the deposit and the balance, and the seller is owed the amount (decision
 * D6: the platform collects the money, real payouts come later). Like payments, orders are financial
 * records: deleting the listing keeps the order (listing becomes null, so the title and image are copied),
 * and a user with orders can't be hard-deleted.
 */
@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_orders_buyer", columnList = "buyer_id"),
        @Index(name = "idx_orders_seller", columnList = "seller_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"listing", "buyer", "seller", "payment"})
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "listing_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Listing listing;

    /** Copied from the listing, which can be deleted later. */
    @Column(nullable = false, length = 120)
    private String listingTitle;

    @Column(length = 500)
    private String listingImageUrl;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "buyer_id", nullable = false)
    private User buyer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    /** The listing price at the time of sale, in minor units. */
    @Column(nullable = false)
    private Long itemPriceCents;

    /** What the buyer paid to reserve, which counts toward the price. */
    @Column(nullable = false)
    private Long depositCents;

    /** What the buyer paid at Buy: the item price minus the deposit. */
    @Column(nullable = false)
    private Long balanceCents;

    /** ISO 4217, lower-case, as on the payment. */
    @Column(nullable = false, length = 3)
    private String currency;

    /** Copied from the listing at the time of sale, so it survives edits and deletion. */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private MeetupSpot meetupSpot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private OrderStatus status = OrderStatus.PAID;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PayoutStatus payoutStatus = PayoutStatus.PENDING;

    /** The PURCHASE payment that created this order. Unique, so a replayed webhook can't create a second order. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", unique = true)
    private Payment payment;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant completedAt;

    /** Set when the buyer reports a problem. The order is then frozen until an admin resolves it. */
    private Instant problemReportedAt;

    @Column(length = 1000)
    private String problemReason;

    public boolean hasOpenProblem() {
        return problemReportedAt != null && status == OrderStatus.PAID;
    }
}
