package com.campus.marketplace.service;

import com.campus.marketplace.dto.UnreadCountResponse;
import com.campus.marketplace.service.ChatEvents.MessageSent;
import com.campus.marketplace.service.ChatEvents.MessagesRead;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

/**
 * Pushes chat changes to connected clients over STOMP. Runs after the database commit, so a client is never told
 * about something that was rolled back, and a push failure never undoes a saved message: REST is the source of
 * truth, and a client that missed a push catches up by refetching.
 * <p>
 * Destinations (all per user, {@code /user} prefix added by the client):
 * {@code /queue/messages} new messages (to both people), {@code /queue/unread} the recipient's total unread count,
 * {@code /queue/read} "the other side read your messages".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatPushListener {

    /** Payload of {@code /queue/read}. */
    public record ReadReceipt(Long conversationId, Instant readAt) {
    }

    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageSent(MessageSent event) {
        push(event.recipientId(), "/queue/messages", event.message());
        push(event.senderId(), "/queue/messages", event.message()); // the sender's other tabs and devices
        push(event.recipientId(), "/queue/unread", new UnreadCountResponse(event.recipientUnread()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessagesRead(MessagesRead event) {
        push(event.otherId(), "/queue/read", new ReadReceipt(event.conversationId(), event.readAt()));
        push(event.readerId(), "/queue/unread", new UnreadCountResponse(event.readerUnread()));
    }

    private void push(Long userId, String destination, Object payload) {
        try {
            messagingTemplate.convertAndSendToUser(userId.toString(), destination, payload);
        } catch (RuntimeException e) {
            log.warn("Could not push {} to user {}: {}", destination, userId, e.getMessage());
        }
    }
}
