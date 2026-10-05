package com.campus.marketplace.repository;

import com.campus.marketplace.entity.Report;
import com.campus.marketplace.entity.ReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReportRepository extends JpaRepository<Report, Long> {

    boolean existsByReporterIdAndListingId(Long reporterId, Long listingId);

    long countByStatus(ReportStatus status);

    @EntityGraph(attributePaths = {"listing", "reporter", "reportedUser"})
    Page<Report> findByStatus(ReportStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"listing", "reporter", "reportedUser"})
    Optional<Report> findWithPartiesById(Long id);

    @EntityGraph(attributePaths = "reporter")
    @Query("select r from Report r where r.status = com.campus.marketplace.entity.ReportStatus.OPEN and r.listing.id = :listingId")
    List<Report> findOpenByListing(@Param("listingId") Long listingId);

    @EntityGraph(attributePaths = "reporter")
    @Query("select r from Report r where r.status = com.campus.marketplace.entity.ReportStatus.OPEN and r.reportedUser.id = :userId")
    List<Report> findOpenByReportedUser(@Param("userId") Long userId);
}
