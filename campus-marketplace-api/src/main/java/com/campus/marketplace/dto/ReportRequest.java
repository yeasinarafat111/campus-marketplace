package com.campus.marketplace.dto;

import com.campus.marketplace.entity.ReportReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReportRequest(
        @NotNull(message = "Reason is required")
        ReportReason reason,

        @Size(max = 500, message = "Details must be at most 500 characters")
        String details
) {
    public ReportRequest {
        details = details == null || details.isBlank() ? null : details.trim();
    }
}
