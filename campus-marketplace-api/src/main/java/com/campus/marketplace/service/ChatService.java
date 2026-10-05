package com.campus.marketplace.service;

import com.campus.marketplace.dto.ConversationResponse;
import com.campus.marketplace.dto.MessagePageResponse;
import com.campus.marketplace.dto.MessageRequest;
import com.campus.marketplace.dto.ChatMessageResponse;
import com.campus.marketplace.dto.UnreadCountResponse;
import com.campus.marketplace.entity.Conversation;
import com.campus.marketplace.entity.Listing;
import com.campus.marketplace.entity.Message;
import com.campus.marketplace.entity.User;
import com.campus.marketplace.exception.BadRequestException;
import com.campus.marketplace.exception.ForbiddenException;
import com.campus.marketplace.exception.ResourceNotFoundException;
import com.campus.marketplace.repository.ConversationRepository;
import com.campus.marketplace.repository.ListingRepository;
import com.campus.marketplace.repository.MessageRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Chat between a buyer and a listing's seller. REST is the source of truth for every write, so validation,
 * error handling and authentication are the same as everywhere else; the WebSocket only pushes what was already
 * saved (see {@link ChatPushListener}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    static final int MAX_INBOX = 200;
    static final int DEFAULT_PAGE = 30;
    static final int MAX_PAGE = 100;

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ListingRepository listingRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUser;
    private final MessageRateLimiter rateLimiter;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactionTemplate;

    /** A conversation, and whether this call created it. */
    public record Opened(ConversationResponse conversation, boolean created) {
    }

    /**
     * Gets the caller's conversation about a listing, creating it the first time. Idempotent, including when two
     * requests race (the unique (listing, buyer) constraint decides and the loser reads the winner's row).
     * You can't message yourself about your own listing.
     */
    public Opened getOrCreate(Long listingId) {
        Long userId = currentUser.requireId();
        Boolean[] created = {false};
        Long conversationId;
        try {
            conversationId = transactionTemplate.execute(tx -> {
                Listing listing = listingRepository.findWithOwnerById(listingId)
                        .filter(l -> l.getOwner().isActive())
                        .filter(l -> l.getStatus() != com.campus.marketplace.entity.ListingStatus.PENDING_PAYMENT)
                        .orElseThrow(() -> new ResourceNotFoundException("Listing " + listingId + " not found"));
                if (listing.getOwner().getId().equals(userId)) {
                    throw new BadRequestException("You can't message yourself about your own listing");
                }
                var existing = conversationRepository.findByListingIdAndBuyerId(listingId, userId);
                if (existing.isPresent()) {
                    return existing.get().getId();
                }
                User buyer = userRepository.getReferenceById(userId);
                Conversation conversation = conversationRepository.saveAndFlush(Conversation.builder()
                        .listing(listing)
                        .listingTitle(listing.getTitle())
                        .listingImageUrl(listing.getImageUrl())
                        .buyer(buyer)
                        .seller(listing.getOwner())
                        .lastMessageAt(Instant.now())
                        .build());
                created[0] = true;
                return conversation.getId();
            });
        } catch (DataIntegrityViolationException e) {
            // Lost a race with another request for the same (listing, buyer): use the row it created.
            created[0] = false;
            conversationId = transactionTemplate.execute(tx ->
                    conversationRepository.findByListingIdAndBuyerId(listingId, userId).orElseThrow().getId());
        }
        Long id = conversationId;
        ConversationResponse response = transactionTemplate.execute(tx -> {
            Conversation c = conversationRepository.findWithPartiesById(id).orElseThrow();
            return view(c, userId);
        });
        return new Opened(response, created[0]);
    }

    /** The caller's conversations, newest activity first, each with its latest message and unread count. */
    @Transactional(readOnly = true)
    public List<ConversationResponse> inbox() {
        Long userId = currentUser.requireId();
        List<Conversation> conversations = conversationRepository.findForUser(userId, PageRequest.of(0, MAX_INBOX));
        if (conversations.isEmpty()) {
            return List.of();
        }
        List<Long> ids = conversations.stream().map(Conversation::getId).toList();
        Map<Long, Message> latest = messageRepository.findLatestOf(ids).stream()
                .collect(Collectors.toMap(m -> m.getConversation().getId(), m -> m));
        Map<Long, Long> unread = new HashMap<>();
        for (Object[] row : messageRepository.countUnreadPerConversation(ids, userId)) {
            unread.put((Long) row[0], (Long) row[1]);
        }
        return conversations.stream()
                .map(c -> ConversationResponse.of(c, userId, latest.get(c.getId()), unread.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    /** A page of a thread, oldest first. Participants only. */
    @Transactional(readOnly = true)
    public MessagePageResponse messages(Long conversationId, Long beforeId, Integer size) {
        requireParticipant(conversationId);
        int pageSize = size == null ? DEFAULT_PAGE : size;
        if (pageSize < 1 || pageSize > MAX_PAGE) {
            throw new BadRequestException("size must be between 1 and " + MAX_PAGE);
        }
        long cursor = beforeId == null ? Long.MAX_VALUE : beforeId;
        List<Message> page = new ArrayList<>(messageRepository.findByConversationIdAndIdLessThanOrderByIdDesc(
                conversationId, cursor, PageRequest.of(0, pageSize + 1)));
        boolean hasMore = page.size() > pageSize;
        if (hasMore) {
            page.remove(page.size() - 1);
        }
        Collections.reverse(page);
        return new MessagePageResponse(page.stream().map(ChatMessageResponse::from).toList(), hasMore);
    }

    /**
     * Saves a message, then (after the commit) pushes it to both people. Participants only; at most 20 a
     * minute per user.
     */
    @Transactional
    public ChatMessageResponse send(Long conversationId, MessageRequest request) {
        Long userId = currentUser.requireId();
        Conversation conversation = requireParticipant(conversationId);
        rateLimiter.check(userId);

        Message message = messageRepository.save(Message.builder()
                .conversation(conversation)
                .sender(userRepository.getReferenceById(userId))
                .body(request.body())
                .build());
        conversation.setLastMessageAt(message.getCreatedAt() != null ? message.getCreatedAt() : Instant.now());

        Long recipientId = conversation.otherParty(userId).getId();
        ChatMessageResponse response = new ChatMessageResponse(message.getId(), conversationId, userId, message.getBody(),
                message.getCreatedAt(), null);
        events.publishEvent(new ChatEvents.MessageSent(response, userId, recipientId,
                messageRepository.countUnreadFor(recipientId)));
        return response;
    }

    /** Marks what the other side sent as read, and tells them (and the caller's other tabs). Idempotent. */
    @Transactional
    public UnreadCountResponse markRead(Long conversationId) {
        Long userId = currentUser.requireId();
        Conversation conversation = requireParticipant(conversationId);
        Instant now = Instant.now();
        int marked = messageRepository.markRead(conversationId, userId, now);
        long unread = messageRepository.countUnreadFor(userId);
        if (marked > 0) {
            events.publishEvent(new ChatEvents.MessagesRead(conversationId, userId,
                    conversation.otherParty(userId).getId(), now, unread));
        }
        return new UnreadCountResponse(unread);
    }

    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount() {
        return new UnreadCountResponse(messageRepository.countUnreadFor(currentUser.requireId()));
    }

    private ConversationResponse view(Conversation conversation, Long userId) {
        Message latest = messageRepository.findLatestOf(List.of(conversation.getId())).stream().findFirst().orElse(null);
        long unread = messageRepository.countUnreadPerConversation(List.of(conversation.getId()), userId).stream()
                .mapToLong(r -> (Long) r[1]).sum();
        return ConversationResponse.of(conversation, userId, latest, unread);
    }

    /** 404 if it doesn't exist, 403 if the caller isn't one of its two people. */
    private Conversation requireParticipant(Long conversationId) {
        Conversation conversation = conversationRepository.findWithPartiesById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation " + conversationId + " not found"));
        if (!conversation.isParticipant(currentUser.requireId())) {
            throw new ForbiddenException("You're not part of this conversation");
        }
        return conversation;
    }
}
