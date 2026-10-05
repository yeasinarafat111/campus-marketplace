package com.campus.marketplace.service;

import com.campus.marketplace.dto.NotificationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pushes notification changes to {@code /user/queue/notifications} after the commit. A failed push is only logged:
 * the REST list is the source of truth and the page refetches it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPushListener {

    /** {@code notification} is null when only the unread count changed. */
    public record Push(NotificationResponse notification, long unread) {
    }

    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(NotificationEvents.Created event) {
        push(event.userId(), new Push(event.notification(), event.unread()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUnreadChanged(NotificationEvents.UnreadChanged event) {
        push(event.userId(), new Push(null, event.unread()));
    }

    private void push(Long userId, Push payload) {
        try {
            messagingTemplate.convertAndSendToUser(userId.toString(), "/queue/notifications", payload);
        } catch (RuntimeException e) {
            log.warn("Could not push a notification to user {}: {}", userId, e.getMessage());
        }
    }
}
