package com.campus.marketplace.repository;

import com.campus.marketplace.entity.Conversation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    @EntityGraph(attributePaths = {"buyer", "seller", "listing"})
    Optional<Conversation> findWithPartiesById(Long id);

    Optional<Conversation> findByListingIdAndBuyerId(Long listingId, Long buyerId);

    /** The caller's conversations, most recently active first. */
    @EntityGraph(attributePaths = {"buyer", "seller", "listing"})
    @Query("select c from Conversation c where c.buyer.id = :userId or c.seller.id = :userId "
            + "order by c.lastMessageAt desc, c.id desc")
    List<Conversation> findForUser(@Param("userId") Long userId, Pageable pageable);
}
