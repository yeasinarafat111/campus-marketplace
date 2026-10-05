package com.campus.marketplace.controller;

import com.campus.marketplace.dto.ConversationRequest;
import com.campus.marketplace.dto.ConversationResponse;
import com.campus.marketplace.dto.MessagePageResponse;
import com.campus.marketplace.dto.MessageRequest;
import com.campus.marketplace.dto.ChatMessageResponse;
import com.campus.marketplace.dto.UnreadCountResponse;
import com.campus.marketplace.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Chat. Every route needs a login (SecurityConfig); the service checks the caller is part of the conversation. */
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ChatService chatService;

    /** Gets or creates the caller's conversation about a listing: 201 the first time, 200 after that. */
    @PostMapping
    public ResponseEntity<ConversationResponse> open(@Valid @RequestBody ConversationRequest request) {
        ChatService.Opened opened = chatService.getOrCreate(request.listingId());
        return ResponseEntity.status(opened.created() ? HttpStatus.CREATED : HttpStatus.OK).body(opened.conversation());
    }

    @GetMapping
    public List<ConversationResponse> inbox() {
        return chatService.inbox();
    }

    // Literal path: matched ahead of /{id}/... (which has more segments anyway).
    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount() {
        return chatService.unreadCount();
    }

    @GetMapping("/{id}/messages")
    public MessagePageResponse messages(@PathVariable Long id,
                                        @RequestParam(required = false) Long beforeId,
                                        @RequestParam(required = false) Integer size) {
        return chatService.messages(id, beforeId, size);
    }

    @PostMapping("/{id}/messages")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public ChatMessageResponse send(@PathVariable Long id, @Valid @RequestBody MessageRequest request) {
        return chatService.send(id, request);
    }

    @PostMapping("/{id}/read")
    public UnreadCountResponse markRead(@PathVariable Long id) {
        return chatService.markRead(id);
    }
}
