package com.campus.marketplace.repository;

import com.campus.marketplace.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    /** Newest first, older than {@code beforeId}: the cursor for paging back through a thread. */
    @EntityGraph(attributePaths = "sender")
    List<Message> findByConversationIdAndIdLessThanOrderByIdDesc(Long conversationId, Long beforeId, Pageable pageable);

    /** The latest message of each given conversation. */
    @Query("select m from Message m join fetch m.sender where m.id in "
            + "(select max(m2.id) from Message m2 where m2.conversation.id in :ids group by m2.conversation.id)")
    List<Message> findLatestOf(@Param("ids") Collection<Long> conversationIds);

    /** Unread messages per conversation (sent by someone else, not yet read): rows of [conversationId, count]. */
    @Query("select m.conversation.id, count(m) from Message m where m.conversation.id in :ids "
            + "and m.sender.id <> :userId and m.readAt is null group by m.conversation.id")
    List<Object[]> countUnreadPerConversation(@Param("ids") Collection<Long> conversationIds, @Param("userId") Long userId);

    @Query("select count(m) from Message m where m.readAt is null and m.sender.id <> :userId "
            + "and (m.conversation.buyer.id = :userId or m.conversation.seller.id = :userId)")
    long countUnreadFor(@Param("userId") Long userId);

    /** Marks everything the other side sent in this conversation as read. Returns how many were unread. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Message m set m.readAt = :now where m.conversation.id = :conversationId "
            + "and m.sender.id <> :readerId and m.readAt is null")
    int markRead(@Param("conversationId") Long conversationId, @Param("readerId") Long readerId, @Param("now") Instant now);
}
