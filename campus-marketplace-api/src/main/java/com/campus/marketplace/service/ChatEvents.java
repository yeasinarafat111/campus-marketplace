package com.campus.marketplace.service;

import com.campus.marketplace.dto.ChatMessageResponse;

import java.time.Instant;

/** Facts about committed chat changes, turned into WebSocket pushes after the commit (see ChatPushListener). */
public final class ChatEvents {

    private ChatEvents() {
    }

    public record MessageSent(ChatMessageResponse message, Long senderId, Long recipientId, long recipientUnread) {
    }

    public record MessagesRead(Long conversationId, Long readerId, Long otherId, Instant readAt, long readerUnread) {
    }
}
