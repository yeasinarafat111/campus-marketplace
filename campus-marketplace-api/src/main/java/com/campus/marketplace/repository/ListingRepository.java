package com.campus.marketplace.repository;

import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Search with {@code findAll(ListingSpecifications.matching(criteria), pageable)}.
 */
public interface ListingRepository extends JpaRepository<Listing, Long>, JpaSpecificationExecutor<Listing> {

    /**
     * Fetches each listing's owner in the same query, so mapping a page of results to DTOs doesn't
     * trigger one extra query per listing.
     */
    @Override
    @EntityGraph(attributePaths = "owner")
    Page<Listing> findAll(Specification<Listing> spec, Pageable pageable);

    @EntityGraph(attributePaths = "owner")
    Optional<Listing> findWithOwnerById(Long id);

    /** Owner and reserver together, for deciding who may do what to a reserved listing outside a transaction. */
    @EntityGraph(attributePaths = {"owner", "reservedBy"})
    Optional<Listing> findWithPartiesById(Long id);

    /** SELECT … FOR UPDATE, so two payments for the same listing can't both apply their effect. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Listing l where l.id = :id")
    Optional<Listing> findForUpdateById(@Param("id") Long id);

    @EntityGraph(attributePaths = "owner")
    Page<Listing> findByOwner(User owner, Pageable pageable);

    /** Active listings per category, for sellers that haven't been deactivated. Categories with none are absent. */
    @Query("""
            select l.category, count(l) from Listing l
            where l.status = com.campus.marketplace.entity.ListingStatus.ACTIVE and l.owner.active = true
            group by l.category""")
    List<Object[]> countActiveByCategory();

    /**
     * Frees every reservation whose time is up. A single conditional UPDATE, so it is safe to run on several
     * instances at once and races cleanly with the purchase webhook (which locks the row): whichever gets
     * the row first wins, and the other sees the new state. Does not refund (decision D5).
     */
    @Modifying
    @Query("""
            update Listing l set l.reservedBy = null, l.reservedAt = null, l.reservationExpiresAt = null
            where l.reservedBy is not null and l.reservationExpiresAt < :now""")
    int releaseExpiredReservations(@Param("now") Instant now);

    /**
     * Reservations made before expiry existed have no end time. Give them one full window from now rather
     * than expiring them at once, since those buyers paid for an open-ended hold.
     */
    @Modifying
    @Query("""
            update Listing l set l.reservationExpiresAt = :expiresAt
            where l.reservedBy is not null and l.reservationExpiresAt is null""")
    int backfillReservationExpiry(@Param("expiresAt") Instant expiresAt);
}
