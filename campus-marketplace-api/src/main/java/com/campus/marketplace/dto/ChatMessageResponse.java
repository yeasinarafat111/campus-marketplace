package com.campus.marketplace.dto;

import com.campus.marketplace.entity.Message;

import java.time.Instant;

/** A chat message. {@code readAt} is when the other side saw it (null until then). */
public record ChatMessageResponse(Long id, Long conversationId, Long senderId, String body, Instant createdAt, Instant readAt) {

    /** Call inside a transaction (reads the lazy conversation and sender ids). */
    public static ChatMessageResponse from(Message message) {
        return new ChatMessageResponse(message.getId(), message.getConversation().getId(), message.getSender().getId(),
                message.getBody(), message.getCreatedAt(), message.getReadAt());
    }
}
