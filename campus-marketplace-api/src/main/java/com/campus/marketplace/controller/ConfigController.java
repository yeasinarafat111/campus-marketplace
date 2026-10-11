package com.campus.marketplace.controller;

import com.campus.marketplace.config.AssistantProperties;
import com.campus.marketplace.dto.PublicConfigResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public feature switches for the frontend (see SecurityConfig). */
@RestController
@RequiredArgsConstructor
public class ConfigController {

    private final AssistantProperties assistant;

    @GetMapping("/api/config")
    public PublicConfigResponse config() {
        return new PublicConfigResponse(assistant.isAvailable(), assistant.isVerdictAvailable(),
                assistant.isAvailable() && assistant.searchEnabled(), assistant.bdtPerUsd());
    }
}
