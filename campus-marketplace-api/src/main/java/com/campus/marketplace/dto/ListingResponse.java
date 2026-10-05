package com.campus.marketplace.dto;

import com.campus.marketplace.entity.Category;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.User;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A listing as the API returns it. Everything up to {@code reserved} is public. The last three fields depend
 * on who is asking and are empty (false, null, null) for everyone except the two sides of a reservation or
 * sale: {@code reservedByMe}, {@code reservationExpiresAt} and {@code counterparty}, the other side's name and
 * phone. Search results never carry them.
 */
public record ListingResponse(
        Long id,
        String title,
        String description,
        BigDecimal price,
        Category category,
        String imageUrl,
        ListingStatus status,
        Instant createdAt,
        Owner owner,
        boolean featured,
        Instant featuredUntil,
        boolean reserved,
        boolean reservedByMe,
        Instant reservationExpiresAt,
        Contact counterparty
) {
    /** Public seller info. The email and phone are deliberately not exposed. */
    public record Owner(Long id, String fullName, Instant memberSince) {
    }

    /**
     * The other side of a trade, shown only to the two parties: to the seller, the buyer; to the buyer, the
     * seller. The phone is null for accounts that haven't added one.
     */
    public record Contact(Long id, String fullName, String phone) {
        static Contact of(User user) {
            return new Contact(user.getId(), user.getFullName(), user.getPhone());
        }
    }

    /** Call inside a transaction: reads the (lazy) owner. The public view, with no viewer-specific fields. */
    public static ListingResponse from(Listing listing) {
        return from(listing, null);
    }

    /** As {@link #from(Listing)}, plus the parts only {@code viewerId} may see. Call inside a transaction. */
    public static ListingResponse from(Listing listing, Long viewerId) {
        User owner = listing.getOwner();
        boolean reservedByMe = false;
        Instant expiresAt = null;
        Contact counterparty = null;

        if (viewerId != null) {
            if (listing.getStatus() == ListingStatus.ACTIVE && listing.isReserved()) {
                User reserver = listing.getReservedBy();
                if (viewerId.equals(reserver.getId())) {
                    reservedByMe = true;
                    expiresAt = listing.getReservationExpiresAt();
                    counterparty = Contact.of(owner);
                } else if (viewerId.equals(owner.getId())) {
                    expiresAt = listing.getReservationExpiresAt();
                    counterparty = Contact.of(reserver);
                }
            } else if (listing.getStatus() == ListingStatus.SOLD && listing.getBuyer() != null) {
                User buyer = listing.getBuyer();
                if (viewerId.equals(buyer.getId())) {
                    counterparty = Contact.of(owner);
                } else if (viewerId.equals(owner.getId())) {
                    counterparty = Contact.of(buyer);
                }
            }
        }

        return new ListingResponse(
                listing.getId(),
                listing.getTitle(),
                listing.getDescription(),
                listing.getPrice(),
                listing.getCategory(),
                listing.getImageUrl(),
                listing.getStatus(),
                listing.getCreatedAt(),
                new Owner(owner.getId(), owner.getFullName(), owner.getCreatedAt()),
                listing.isFeatured(Instant.now()),
                listing.getFeaturedUntil(),
                listing.isReserved(),
                reservedByMe,
                expiresAt,
                counterparty);
    }
}
