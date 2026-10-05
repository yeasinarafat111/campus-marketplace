package com.campus.marketplace.dto;

import com.campus.marketplace.entity.ReportAction;
import jakarta.validation.constraints.NotNull;

public record ResolveReportRequest(
        @NotNull(message = "Action is required")
        ReportAction action
) {
}
