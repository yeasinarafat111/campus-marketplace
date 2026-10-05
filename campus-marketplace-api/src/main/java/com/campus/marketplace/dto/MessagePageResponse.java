package com.campus.marketplace.dto;

import java.util.List;

/**
 * One page of a thread, oldest first within the page. {@code hasMore} says whether older messages exist; ask for
 * them with {@code beforeId} set to the first message's id.
 */
public record MessagePageResponse(List<ChatMessageResponse> messages, boolean hasMore) {
}
