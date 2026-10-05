package com.campus.marketplace.service;

import com.campus.marketplace.dto.NotificationResponse;
import com.campus.marketplace.dto.PageResponse;
import com.campus.marketplace.entity.Notification;
import com.campus.marketplace.entity.NotificationType;
import com.campus.marketplace.exception.BadRequestException;
import com.campus.marketplace.exception.ResourceNotFoundException;
import com.campus.marketplace.repository.NotificationRepository;
import com.campus.marketplace.repository.UserRepository;
import com.campus.marketplace.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * In-app notifications. {@link #notify} joins the caller's transaction, so a notice is saved (or lost) together with
 * the change it describes, and is pushed live only once that change has committed.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final int MAX_PAGE_SIZE = 50;

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUser;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional
    public void notify(Long userId, NotificationType type, String title, String body, String link) {
        notifyOnce(userId, type, title, body, link, null);
    }

    /** As {@link #notify}, but a second call with the same key for the same user does nothing. Returns whether one was created. */
    @Transactional
    public boolean notifyOnce(Long userId, NotificationType type, String title, String body, String link, String dedupeKey) {
        if (dedupeKey != null && notificationRepository.existsByUserIdAndDedupeKey(userId, dedupeKey)) {
            return false;
        }
        Notification saved = notificationRepository.saveAndFlush(Notification.builder()
                .user(userRepository.getReferenceById(userId))
                .type(type)
                .title(trim(title, 160))
                .body(trim(body, 400))
                .link(link)
                .dedupeKey(dedupeKey)
                .build());
        events.publishEvent(new NotificationEvents.Created(userId, NotificationResponse.from(saved),
                notificationRepository.countByUserIdAndReadAtIsNull(userId)));
        return true;
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> mine(int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Long userId = currentUser.requireId();
        return PageResponse.of(notificationRepository.findByUserId(userId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")))),
                NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return notificationRepository.countByUserIdAndReadAtIsNull(currentUser.requireId());
    }

    /** Idempotent. Someone else's (or a missing) notification is a 404. */
    @Transactional
    public void markRead(Long id) {
        Long userId = currentUser.requireId();
        if (!notificationRepository.existsByIdAndUserId(id, userId)) {
            throw new ResourceNotFoundException("Notification " + id + " not found");
        }
        if (notificationRepository.markRead(id, userId, Instant.now(clock)) > 0) {
            events.publishEvent(new NotificationEvents.UnreadChanged(userId, notificationRepository.countByUserIdAndReadAtIsNull(userId)));
        }
    }

    @Transactional
    public void markAllRead() {
        Long userId = currentUser.requireId();
        if (notificationRepository.markAllRead(userId, Instant.now(clock)) > 0) {
            events.publishEvent(new NotificationEvents.UnreadChanged(userId, 0));
        }
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
