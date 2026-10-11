package com.campus.marketplace.service;

import com.campus.marketplace.dto.AssistantRequest;

import java.util.List;

/**
 * Asks a language model with web search. Implemented by {@link GeminiAssistantGateway}; {@link FakeAssistantGateway}
 * stands in for tests and local demos.
 */
public interface AssistantGateway {

    /**
     * @throws com.campus.marketplace.exception.AssistantUnavailableException if the provider is busy, slow, failing
     *                                                                         or not configured
     */
    Reply generate(Prompt prompt);

    /**
     * @param mode              what is being asked, for logs and the fake; the real model sees only the text
     * @param systemInstruction our rules. Never contains seller-written text
     * @param turns             the conversation, alternating user and model, starting with the user. The first turn
     *                          carries the product data block (and the photo, if any)
     * @param webSearch         let the model use Google Search (grounding)
     */
    record Prompt(AssistantRequest.Mode mode, String systemInstruction, List<Turn> turns, Image image, boolean webSearch) {
    }

    /** {@code role} is "user" or "model", as Gemini names them. */
    record Turn(String role, String text) {
    }

    /** The listing's cover photo, attached to the first turn. */
    record Image(String mimeType, byte[] data) {
    }

    /** {@code text} may be empty when the model refused or was blocked. */
    record Reply(String text, List<Source> sources) {
    }

    record Source(String title, String url) {
    }
}
