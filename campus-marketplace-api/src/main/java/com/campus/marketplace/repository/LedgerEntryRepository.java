package com.campus.marketplace.repository;

import com.campus.marketplace.entity.LedgerEntry;
import com.campus.marketplace.entity.LedgerType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    /** Entries in [from, to), oldest first, for the report's totals and series. */
    List<LedgerEntry> findByOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAscIdAsc(Instant from, Instant to);

    List<LedgerEntry> findByPaymentIdAndTypeOrderById(Long paymentId, LedgerType type);

    boolean existsByPaymentIdAndType(Long paymentId, LedgerType type);

    /** The history table: newest first, optionally narrowed by date range and type. */
    @Query("""
            select e from LedgerEntry e
            where e.occurredAt >= :from and e.occurredAt < :to and (:type is null or e.type = :type)
            order by e.occurredAt desc, e.id desc""")
    Page<LedgerEntry> search(@Param("from") Instant from, @Param("to") Instant to, @Param("type") LedgerType type, Pageable pageable);

    /** Every match, oldest first, for the CSV export. */
    @Query("""
            select e from LedgerEntry e
            where e.occurredAt >= :from and e.occurredAt < :to and (:type is null or e.type = :type)
            order by e.occurredAt asc, e.id asc""")
    List<LedgerEntry> searchAll(@Param("from") Instant from, @Param("to") Instant to, @Param("type") LedgerType type, Pageable limit);
}
