package com.campus.marketplace.exception;

/** The AI assistant is switched off, not configured, or its provider is busy or failing. Always a 503. */
public class AssistantUnavailableException extends RuntimeException {

    public static final String BUSY = "The assistant is busy, try again in a minute";
    public static final String OFF = "The AI assistant is turned off right now";

    public AssistantUnavailableException(String message) {
        super(message);
    }

    public static AssistantUnavailableException busy() {
        return new AssistantUnavailableException(BUSY);
    }

    public static AssistantUnavailableException off() {
        return new AssistantUnavailableException(OFF);
    }
}
