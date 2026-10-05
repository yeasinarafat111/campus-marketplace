package com.campus.marketplace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
 * A chat between a buyer and a listing's seller. One per (listing, buyer). It outlives the listing: when the
 * listing is deleted the link becomes null, and the title and image copied here keep the inbox readable.
 */
@Entity
@Table(name = "conversations",
        uniqueConstraints = @UniqueConstraint(name = "uk_conversation_listing_buyer", columnNames = {"listing_id", "buyer_id"}),
        indexes = {
                @Index(name = "idx_conversations_buyer", columnList = "buyer_id"),
                @Index(name = "idx_conversations_seller", columnList = "seller_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"listing", "buyer", "seller"})
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "listing_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Listing listing;

    @Column(length = 120)
    private String listingTitle;

    @Column(length = 500)
    private String listingImageUrl;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "buyer_id", nullable = false)
    private User buyer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** The newest message's time (or the creation time while empty), for ordering the inbox. */
    @Column(nullable = false)
    private Instant lastMessageAt;

    public boolean isParticipant(Long userId) {
        return buyer.getId().equals(userId) || seller.getId().equals(userId);
    }

    public User otherParty(Long userId) {
        return buyer.getId().equals(userId) ? seller : buyer;
    }
}
