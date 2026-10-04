package com.campus.marketplace.exception;

/** The webhook request isn't a genuine Stripe event (bad, missing, or stale signature). Returned as 400. */
public class WebhookSignatureException extends RuntimeException {

    public WebhookSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
