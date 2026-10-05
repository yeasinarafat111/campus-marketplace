package com.campus.marketplace.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** The listing's photo ids in the new display order. Must be exactly the listing's current photos. */
public record ReorderImagesRequest(
        @NotNull(message = "imageIds is required")
        @Size(min = 1, max = 5, message = "imageIds must list between 1 and 5 photos")
        List<Long> imageIds
) {
}
