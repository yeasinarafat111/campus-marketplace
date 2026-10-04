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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

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

    /** SELECT … FOR UPDATE, so two payments for the same listing can't both apply their effect. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Listing l where l.id = :id")
    Optional<Listing> findForUpdateById(@Param("id") Long id);

    @EntityGraph(attributePaths = "owner")
    Page<Listing> findByOwner(User owner, Pageable pageable);
}
