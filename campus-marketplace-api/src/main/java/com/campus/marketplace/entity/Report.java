package com.campus.marketplace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/**
 * A user's report of a listing. Kept after the listing is deleted (the link is nulled, the title is remembered), so
 * the moderation history survives the very action it led to. One report per user per listing.
 */
@Entity
@Table(name = "reports",
        uniqueConstraints = @UniqueConstraint(name = "uk_reports_reporter_listing", columnNames = {"reporter_id", "listing_id"}),
        indexes = @Index(name = "idx_reports_status_created", columnList = "status, created_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"listing", "reporter", "reportedUser", "resolvedBy"})
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "listing_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Listing listing;

    /** Remembered so the queue still says what was reported after the listing is gone. */
    @Column(nullable = false, length = 120)
    private String listingTitle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User reporter;

    /** The listing's owner when it was reported. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_user_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User reportedUser;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReportReason reason;

    @Column(length = 500)
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ReportStatus status = ReportStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ReportAction resolution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User resolvedBy;

    private Instant resolvedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
