package com.campus.marketplace.controller;

import com.campus.marketplace.dto.AssistantRequest;
import com.campus.marketplace.dto.AssistantResponse;
import com.campus.marketplace.service.AssistantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** "Ask AI" about a listing. Logged-in users only (POST, so SecurityConfig's anyRequest rule applies). */
@RestController
@RequiredArgsConstructor
public class AssistantController {

    private final AssistantService assistantService;

    @PostMapping("/api/listings/{id}/assistant")
    public AssistantResponse ask(@PathVariable Long id, @Valid @RequestBody AssistantRequest request) {
        return assistantService.ask(id, request);
    }
}
