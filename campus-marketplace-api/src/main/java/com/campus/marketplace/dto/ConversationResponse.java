package com.campus.marketplace.dto;

import com.campus.marketplace.entity.Conversation;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.Message;
import com.campus.marketplace.entity.User;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A conversation as one participant sees it: who they're talking to, which listing it is about, the latest
 * message and how many are unread. Phone numbers are never part of it.
 */
public record ConversationResponse(
        Long id,
        ListingSummary listing,
        Person counterparty,
        LastMessage lastMessage,
        Instant lastMessageAt,
        long unreadCount
) {
    /** The listing's current title and image while it exists, the copies taken at the start once it is gone. */
    public record ListingSummary(Long id, String title, String imageUrl, BigDecimal price, String status) {
    }

    public record Person(Long id, String fullName) {
    }

    public record LastMessage(String preview, Instant createdAt, boolean mine) {
        static final int PREVIEW_LENGTH = 100;
    }

    /** Call inside a transaction. {@code latest} may be null for a conversation with no messages yet. */
    public static ConversationResponse of(Conversation conversation, Long viewerId, Message latest, long unread) {
        Listing listing = conversation.getListing();
        ListingSummary summary = listing == null
                ? new ListingSummary(null, conversation.getListingTitle(), conversation.getListingImageUrl(), null, null)
                : new ListingSummary(listing.getId(), listing.getTitle(), listing.getImageUrl(), listing.getPrice(),
                        listing.getStatus().name());
        User other = conversation.otherParty(viewerId);
        LastMessage last = latest == null ? null : new LastMessage(preview(latest.getBody()), latest.getCreatedAt(),
                latest.getSender().getId().equals(viewerId));
        return new ConversationResponse(conversation.getId(), summary, new Person(other.getId(), other.getFullName()),
                last, conversation.getLastMessageAt(), unread);
    }

    private static String preview(String body) {
        String oneLine = body.replaceAll("\\s+", " ");
        return oneLine.length() <= LastMessage.PREVIEW_LENGTH ? oneLine : oneLine.substring(0, LastMessage.PREVIEW_LENGTH) + "…";
    }
}
