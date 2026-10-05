package com.campus.marketplace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReportProblemRequest(
        @NotBlank(message = "Please describe the problem")
        @Size(max = 1000, message = "Description must be at most 1000 characters")
        String reason
) {
    public ReportProblemRequest {
        reason = reason == null ? null : reason.strip();
    }
}
