package com.campus.marketplace.dto;

import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.Report;
import com.campus.marketplace.entity.ReportAction;
import com.campus.marketplace.entity.ReportReason;
import com.campus.marketplace.entity.ReportStatus;
import com.campus.marketplace.entity.User;

import java.time.Instant;

/** One row of the moderation queue. Call inside a transaction (or with the parties fetched). */
public record AdminReportResponse(
        Long id,
        ReportReason reason,
        String details,
        ReportStatus status,
        ReportAction resolution,
        Instant createdAt,
        Instant resolvedAt,
        String listingTitle,
        /** Null once the listing has been deleted. */
        ListingRef listing,
        Person reporter,
        /** The listing's owner; null if that account is gone. */
        Person reportedUser
) {
    public record ListingRef(Long id, ListingStatus status) {
    }

    public record Person(Long id, String fullName, boolean active) {
        static Person of(User user) {
            return user == null ? null : new Person(user.getId(), user.getFullName(), user.isActive());
        }
    }

    public static AdminReportResponse from(Report r) {
        Listing listing = r.getListing();
        return new AdminReportResponse(
                r.getId(), r.getReason(), r.getDetails(), r.getStatus(), r.getResolution(), r.getCreatedAt(), r.getResolvedAt(),
                r.getListingTitle(),
                listing == null ? null : new ListingRef(listing.getId(), listing.getStatus()),
                Person.of(r.getReporter()), Person.of(r.getReportedUser()));
    }
}
