package com.campus.marketplace.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A question to the AI shopping assistant about one listing.
 *
 * @param mode     SPECS (full specifications), VERDICT (is it a good buy?) or ASK (a free question)
 * @param need     optional: what the student will use it for ("CSE coursework"); tailors SPECS and ASK
 * @param question required for ASK
 * @param history  the previous turns of this chat (ASK only), oldest first. Kept in the browser, never stored here
 */
public record AssistantRequest(
        @NotNull Mode mode,
        @Size(max = 200) String need,
        @Size(max = 500) String question,
        @Size(max = 6, message = "at most 6 previous turns") List<@NotNull @Valid Turn> history
) {
    public static final int MAX_HISTORY = 6;

    public enum Mode { SPECS, VERDICT, ASK }

    public enum Role {
        @JsonProperty("user") USER,
        @JsonProperty("assistant") ASSISTANT
    }

    public record Turn(@NotNull Role role, @NotBlank @Size(max = 2000) String text) {
    }

    @JsonIgnore
    @AssertTrue(message = "question is required for ASK")
    public boolean isQuestionGivenForAsk() {
        return mode != Mode.ASK || hasQuestion();
    }

    public boolean hasNeed() {
        return need != null && !need.isBlank();
    }

    public boolean hasQuestion() {
        return question != null && !question.isBlank();
    }

    public List<Turn> historyOrEmpty() {
        return history == null ? List.of() : history;
    }
}
