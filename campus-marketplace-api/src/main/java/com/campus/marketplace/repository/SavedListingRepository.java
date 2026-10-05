package com.campus.marketplace.repository;

import com.campus.marketplace.entity.SavedListing;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SavedListingRepository extends JpaRepository<SavedListing, Long> {

    boolean existsByUserIdAndListingId(Long userId, Long listingId);

    @Modifying
    @Query("delete from SavedListing s where s.user.id = :userId and s.listing.id = :listingId")
    int deleteByUserAndListing(@Param("userId") Long userId, @Param("listingId") Long listingId);

    /** Newest first. Listings still waiting for their posting fee are never shown. */
    @EntityGraph(attributePaths = {"listing", "listing.owner"})
    @Query("select s from SavedListing s where s.user.id = :userId "
            + "and s.listing.status <> com.campus.marketplace.entity.ListingStatus.PENDING_PAYMENT")
    Page<SavedListing> findVisibleByUser(@Param("userId") Long userId, Pageable pageable);

    @Query("select s.listing.id from SavedListing s where s.user.id = :userId")
    List<Long> findListingIdsByUser(@Param("userId") Long userId);
}
