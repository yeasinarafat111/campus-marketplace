package com.campus.marketplace.service;

import com.campus.marketplace.exception.AssistantUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Canned answers, no network, no key ({@code ASSISTANT_GATEWAY=fake}). For tests and local demos. A question
 * containing "#busy" simulates Gemini failing, so that state can be shown. Tests can script the next reply and read
 * the last prompt.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.assistant.gateway", havingValue = "fake")
public class FakeAssistantGateway implements AssistantGateway {

    static final List<Source> BANGLADESHI_SOURCES = List.of(
            new Source("bikroy.com", "https://vertexaisearch.cloud.google.com/grounding-api-redirect/fake1"),
            new Source("daraz.com.bd", "https://vertexaisearch.cloud.google.com/grounding-api-redirect/fake2"));

    private static final String VERDICT_ANSWER = """
            PRICES_JSON_START
            [{"amountBdt": 4500, "condition": "used", "sourceTitle": "bikroy.com", "sourceUrl": "https://bikroy.com/en/ad/sample-1"},
             {"amountBdt": 5200, "condition": "used", "sourceTitle": "bikroy.com", "sourceUrl": "https://bikroy.com/en/ad/sample-2"},
             {"amountBdt": 6000, "condition": "new", "sourceTitle": "daraz.com.bd", "sourceUrl": "https://www.daraz.com.bd/products/sample"}]
            PRICES_JSON_END
            - Condition and age matter most for this kind of item
            - Check that it powers on and nothing is cracked""";

    private final Duration delay;
    private final AtomicReference<Reply> nextReply = new AtomicReference<>();
    private final AtomicReference<RuntimeException> nextFailure = new AtomicReference<>();
    private final AtomicReference<Prompt> lastPrompt = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    public FakeAssistantGateway(@Value("${app.assistant.fake-delay:0ms}") Duration delay) {
        this.delay = delay;
        log.warn("Using the FAKE assistant gateway: answers are canned, Gemini is never called");
    }

    @Override
    public Reply generate(Prompt prompt) {
        lastPrompt.set(prompt);
        calls.incrementAndGet();
        RuntimeException failure = nextFailure.getAndSet(null);
        if (failure != null) {
            throw failure;
        }
        String last = prompt.turns().get(prompt.turns().size() - 1).text();
        if (last.toLowerCase(Locale.ROOT).contains("#busy")) {
            throw AssistantUnavailableException.busy();
        }
        Reply scripted = nextReply.getAndSet(null);
        if (scripted != null) {
            return scripted;
        }
        pause();
        return switch (prompt.mode()) {
            case SPECS -> new Reply(last.contains("will use it for")
                    ? "PRODUCT: Sample Phone X1 8/128GB\nFake answer, medium confidence\n- Fits your need: yes, for light use\n- Weight: 1.2 kg\n- Battery: 8 hours"
                    : "PRODUCT: Sample Phone X1 8/128GB\nFake answer, medium confidence\n- Weight: 1.2 kg\n- Battery: 8 hours\n- Ports: 2 x USB-C\nCheck in person: screen and battery health.",
                    prompt.webSearch() ? List.of(new Source("example.com", "https://example.com/specs")) : List.of());
            case VERDICT -> new Reply(VERDICT_ANSWER, BANGLADESHI_SOURCES);
            case ASK -> new Reply("Fake answer to: " + firstLine(lastQuestion(prompt)), List.of());
        };
    }

    /** The next call returns this instead of the canned answer. */
    public void respondWith(Reply reply) {
        nextReply.set(reply);
    }

    /** The next call throws this. */
    public void failWith(RuntimeException failure) {
        nextFailure.set(failure);
    }

    public Prompt lastPrompt() {
        return lastPrompt.get();
    }

    public int calls() {
        return calls.get();
    }

    public void reset() {
        nextReply.set(null);
        nextFailure.set(null);
        lastPrompt.set(null);
        calls.set(0);
    }

    private static String lastQuestion(Prompt prompt) {
        String last = prompt.turns().get(prompt.turns().size() - 1).text();
        int split = last.lastIndexOf("\n\n");
        return split >= 0 ? last.substring(split + 2) : last;
    }

    private static String firstLine(String text) {
        String line = text.lines().findFirst().orElse("");
        return line.length() > 120 ? line.substring(0, 120) : line;
    }

    private void pause() {
        if (!delay.isZero()) {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
