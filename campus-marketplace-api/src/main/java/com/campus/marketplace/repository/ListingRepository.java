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
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.QueryHints;

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

    long countByOwnerIdAndStatus(Long ownerId, com.campus.marketplace.entity.ListingStatus status);

    @EntityGraph(attributePaths = "owner")
    Page<Listing> findByOwner(User owner, Pageable pageable);

    /**
     * Deletes listings that have waited for their posting fee since before {@code cutoff}. The payments that
     * pointed at them are kept (the database nulls the link); a fee paid after the delete is refunded by the webhook.
     */
    @Modifying
    @Query("delete from Listing l where l.status = com.campus.marketplace.entity.ListingStatus.PENDING_PAYMENT "
            + "and l.createdAt < :cutoff")
    int deleteStalePending(@Param("cutoff") Instant cutoff);

    /** Active listings per category, for sellers that haven't been deactivated. Categories with none are absent. */
    @Query("""
            select l.category, count(l) from Listing l
            where l.status = com.campus.marketplace.entity.ListingStatus.ACTIVE and l.owner.active = true
            group by l.category""")
    List<Object[]> countActiveByCategory();

    /**
     * Reservations whose time is up, locked for the caller. Rows another transaction holds (a purchase webhook
     * for the same listing, another instance's sweep) are skipped rather than waited for: whoever has them
     * finishes them, and the next sweep sees whatever is left.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select l from Listing l where l.reservedBy is not null and l.reservationExpiresAt < :now")
    List<Listing> findExpiredReservationsForUpdate(@Param("now") Instant now);

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
