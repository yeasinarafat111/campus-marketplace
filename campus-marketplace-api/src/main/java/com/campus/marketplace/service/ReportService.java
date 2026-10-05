package com.campus.marketplace.service;

import com.campus.marketplace.config.CacheNames;
import com.campus.marketplace.dto.AdminReportResponse;
import com.campus.marketplace.dto.PageResponse;
import com.campus.marketplace.dto.ReportRequest;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.ListingStatus;
import com.campus.marketplace.entity.Report;
import com.campus.marketplace.entity.ReportAction;
import com.campus.marketplace.entity.ReportStatus;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.exception.BadRequestException;
import com.campus.marketplace.exception.ConflictException;
import com.campus.marketplace.exception.ResourceNotFoundException;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.ReportRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Reporting a listing, and the admin queue that works through the reports. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private static final int MAX_PAGE_SIZE = 50;

    private final ReportRepository reportRepository;
    private final ListingRepository listingRepository;
    private final UserRepository userRepository;
    private final AdminService adminService;
    private final CurrentUserProvider currentUser;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** One report per user per listing. Not your own listing, and not one that isn't public yet. */
    public void report(Long listingId, ReportRequest request) {
        Long userId = currentUser.requireId();
        Listing listing = listingRepository.findWithOwnerById(listingId)
                .filter(l -> l.getStatus() != ListingStatus.PENDING_PAYMENT)
                .orElseThrow(() -> new ResourceNotFoundException("Listing " + listingId + " not found"));
        if (Objects.equals(listing.getOwner().getId(), userId)) {
            throw new BadRequestException("You can't report your own listing");
        }
        if (reportRepository.existsByReporterIdAndListingId(userId, listingId)) {
            throw new ConflictException("You have already reported this listing");
        }
        try {
            reportRepository.saveAndFlush(Report.builder()
                    .listing(listing)
                    .listingTitle(listing.getTitle())
                    .reporter(userRepository.getReferenceById(userId))
                    .reportedUser(listing.getOwner())
                    .reason(request.reason())
                    .details(request.details())
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("You have already reported this listing");
        }
        log.info("User {} reported listing {} ({})", userId, listingId, request.reason());
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminReportResponse> list(ReportStatus status, int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        // Oldest open first (work the queue in order); newest resolved first (history).
        Sort sort = status == ReportStatus.OPEN
                ? Sort.by(Sort.Direction.ASC, "createdAt").and(Sort.by("id"))
                : Sort.by(Sort.Direction.DESC, "resolvedAt").and(Sort.by(Sort.Direction.DESC, "id"));
        return PageResponse.of(reportRepository.findByStatus(status, PageRequest.of(page, size, sort)),
                AdminReportResponse::from);
    }

    /**
     * Closes a report. DELETE_LISTING and DEACTIVATE_USER also act, and close the other open reports they make moot
     * (the same listing, or the same person), all in one transaction so a failure leaves nothing half done.
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.USER_PROFILE, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.LISTING_BY_ID, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.FEE_QUOTE, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.FEATURED_LISTINGS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CATEGORY_COUNTS, allEntries = true)})
    public AdminReportResponse resolve(Long reportId, ReportAction action) {
        Long adminId = currentUser.requireId();
        return transactionTemplate.execute(tx -> {
            Report report = reportRepository.findWithPartiesById(reportId)
                    .orElseThrow(() -> new ResourceNotFoundException("Report " + reportId + " not found"));
            if (report.getStatus() == ReportStatus.RESOLVED) {
                throw new ConflictException("This report is already resolved");
            }
            List<Report> closing = new ArrayList<>(List.of(report));

            switch (action) {
                case DELETE_LISTING -> {
                    Listing listing = report.getListing();
                    if (listing == null) {
                        throw new ConflictException("The listing was already deleted");
                    }
                    Long listingId = listing.getId();
                    closing.addAll(reportRepository.findOpenByListing(listingId).stream()
                            .filter(r -> !r.getId().equals(reportId)).toList());
                    // The database nulls the link on delete; do it in memory too so the update below can't write the old id back.
                    closing.forEach(r -> r.setListing(null));
                    reportRepository.flush();
                    adminService.deleteListing(listingId);
                }
                case DEACTIVATE_USER -> {
                    User reported = report.getReportedUser();
                    if (reported == null) {
                        throw new ConflictException("That account no longer exists");
                    }
                    closing.addAll(reportRepository.findOpenByReportedUser(reported.getId()).stream()
                            .filter(r -> !r.getId().equals(reportId)).toList());
                    adminService.deactivateUser(reported.getId());
                }
                case RESOLVE -> { }
            }

            Instant now = Instant.now(clock);
            User admin = userRepository.getReferenceById(adminId);
            for (Report r : closing) {
                r.setStatus(ReportStatus.RESOLVED);
                r.setResolution(action);
                r.setResolvedBy(admin);
                r.setResolvedAt(now);
            }
            log.info("Admin {} resolved report {} with {} ({} report(s) closed)", adminId, reportId, action, closing.size());
            return AdminReportResponse.from(report);
        });
    }

    @Transactional(readOnly = true)
    public long openCount() {
        return reportRepository.countByStatus(ReportStatus.OPEN);
    }
}
