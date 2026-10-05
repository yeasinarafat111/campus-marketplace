package com.campus.marketplace.service;

import com.campus.marketplace.dto.NotificationResponse;

/** Facts about committed notification changes, pushed to the user's open pages after the commit. */
public final class NotificationEvents {

    private NotificationEvents() {
    }

    /** A new notice. */
    public record Created(Long userId, NotificationResponse notification, long unread) {
    }

    /** Notices were read (maybe in another tab): everyone's unread count needs to follow. */
    public record UnreadChanged(Long userId, long unread) {
    }
}
