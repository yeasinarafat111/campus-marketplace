package com.campus.marketplace.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RatingRequest(
        @NotNull(message = "Stars are required")
        @Min(value = 1, message = "Stars must be between 1 and 5")
        @Max(value = 5, message = "Stars must be between 1 and 5")
        Integer stars,

        @Size(max = 500, message = "Comment must be at most 500 characters")
        String comment
) {
    public RatingRequest {
        comment = comment == null || comment.isBlank() ? null : comment.trim();
    }
}
