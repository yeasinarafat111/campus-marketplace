package com.campus.marketplace.dto;

import com.campus.marketplace.entity.Message;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MessageRequest(
        @NotBlank(message = "Message can't be empty")
        @Size(max = Message.MAX_BODY_LENGTH, message = "Message must be at most 2000 characters")
        String body
) {
    public MessageRequest {
        body = body == null ? null : body.strip();
    }
}
