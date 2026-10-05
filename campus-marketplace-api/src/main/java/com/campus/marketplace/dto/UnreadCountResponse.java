package com.campus.marketplace.dto;

/** How many messages from other people the caller hasn't read yet, across all conversations. */
public record UnreadCountResponse(long count) {
}
