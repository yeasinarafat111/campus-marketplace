package com.campus.marketplace.dto;

import com.campus.marketplace.entity.Notification;
import com.campus.marketplace.entity.NotificationType;

import java.time.Instant;

public record NotificationResponse(Long id, NotificationType type, String title, String body, String link,
                                   boolean read, Instant createdAt) {

    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getLink(),
                n.getReadAt() != null, n.getCreatedAt());
    }
}
