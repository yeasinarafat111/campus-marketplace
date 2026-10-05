package com.campus.marketplace.dto;

import com.campus.marketplace.entity.Category;
import com.campus.marketplace.validation.ValidListingPrice;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Body for creating a listing and for PUT (full replacement). Status and owner are not accepted here:
 * the owner is the current user, and status changes go through PATCH /{id}/sold.
 */
public record ListingRequest(
        @NotBlank(message = "Title is required")
        @Size(max = 120, message = "Title must be at most 120 characters")
        String title,

        @Size(max = 2000, message = "Description must be at most 2000 characters")
        String description,

        @NotNull(message = "Price is required")
        @DecimalMin(value = "0.00", message = "Price cannot be negative")
        @ValidListingPrice
        @Digits(integer = 8, fraction = 2, message = "Price must have at most 8 digits and 2 decimal places")
        BigDecimal price,

        @NotNull(message = "Category is required")
        Category category,

        // Only http(s), so a stored URL can't be a javascript: or data: payload when rendered.
        @Size(max = 500, message = "Image URL must be at most 500 characters")
        @Pattern(regexp = "^https?://\\S+$", message = "Image URL must be an http:// or https:// URL")
        String imageUrl
) {
    public ListingRequest {
        title = title == null ? null : title.trim();
        description = description == null || description.isBlank() ? null : description.trim();
        imageUrl = imageUrl == null || imageUrl.isBlank() ? null : imageUrl.trim();
    }
}
