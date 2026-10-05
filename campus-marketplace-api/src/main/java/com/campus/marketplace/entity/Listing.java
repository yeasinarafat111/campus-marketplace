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

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "listings", indexes = {
        @Index(name = "idx_listings_status_created_at", columnList = "status, created_at"),
        @Index(name = "idx_listings_category", columnList = "category"),
        @Index(name = "idx_listings_owner", columnList = "owner_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"owner", "description", "reservedBy", "buyer"})
public class Listing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(length = 2000)
    private String description;

    // BigDecimal, not double, so prices like 19.99 are stored and compared exactly.
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Category category;

    @Column(length = 500)
    private String imageUrl;

    /** Where the seller prefers to meet. Optional; null means "not stated". */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private MeetupSpot meetupSpot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ListingStatus status = ListingStatus.ACTIVE;

    // Deleting a user deletes their listings at the database level.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User owner;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** Set by a successful FEATURE_LISTING payment; the listing is featured while this is in the future. */
    private Instant featuredUntil;

    /** Buyer who paid the RESERVE_ITEM deposit, or null. Cleared if that user is deleted. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reserved_by_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User reservedBy;

    private Instant reservedAt;

    /** When the current reservation lapses and the item becomes available again. Null if not reserved. */
    private Instant reservationExpiresAt;

    /**
     * Set when the reserver chooses to pay the balance in cash at the meetup instead of online. The listing stays
     * reserved (and the normal expiry applies) until the seller confirms the handover. Cleared with the reservation.
     */
    private Instant cashChosenAt;

    /** Who bought it, set when the purchase payment succeeds. Null until sold (and for items sold off-platform). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buyer_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User buyer;

    private Instant soldAt;

    /**
     * The highest price (in minor units) this listing's posting covers: the price the fee was paid on, or the
     * free-posting threshold for a listing that needed no fee. The price can't be raised above it (that would
     * dodge the fee); lowering is always fine. Null for listings from before posting fees, which are grandfathered.
     */
    private Long postingFeeBasisCents;

    public void clearReservation() {
        this.reservedBy = null;
        this.reservedAt = null;
        this.reservationExpiresAt = null;
        this.cashChosenAt = null;
    }

    public boolean isFeatured(Instant now) {
        return featuredUntil != null && featuredUntil.isAfter(now);
    }

    /** Doesn't load the buyer: a null check on a lazy association only reads the foreign key. */
    public boolean isReserved() {
        return reservedBy != null;
    }
}
