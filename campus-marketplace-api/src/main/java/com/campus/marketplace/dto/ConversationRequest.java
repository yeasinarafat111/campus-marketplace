package com.campus.marketplace.dto;

import jakarta.validation.constraints.NotNull;

public record ConversationRequest(@NotNull(message = "listingId is required") Long listingId) {
}
