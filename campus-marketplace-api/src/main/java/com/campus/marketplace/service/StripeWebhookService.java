package com.campus.marketplace.service;

import com.campus.marketplace.config.StripeProperties;
import com.campus.marketplace.exception.PaymentProviderException;
import com.campus.marketplace.exception.WebhookSignatureException;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Verifies and dispatches Stripe webhook events. Only {@code checkout.session.*} events are acted on;
 * everything else is acknowledged and ignored.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeWebhookService {

    private final StripeProperties stripeProperties;
    private final PaymentService paymentService;

    public void handle(String payload, String signatureHeader) {
        Event event = verify(payload, signatureHeader);
        switch (event.getType()) {
            case "checkout.session.completed" -> {
                Session session = session(event);
                // "unpaid" happens for delayed methods (e.g. bank debits); those finish with async_payment_succeeded.
                if ("paid".equals(session.getPaymentStatus()) || "no_payment_required".equals(session.getPaymentStatus())) {
                    paymentService.markSucceeded(session.getId(), session.getPaymentIntent());
                } else {
                    log.info("Checkout session {} completed with payment_status={}; waiting for async result",
                            session.getId(), session.getPaymentStatus());
                }
            }
            case "checkout.session.async_payment_succeeded" -> {
                Session session = session(event);
                paymentService.markSucceeded(session.getId(), session.getPaymentIntent());
            }
            case "checkout.session.async_payment_failed", "checkout.session.expired" ->
                    paymentService.markFailed(session(event).getId());
            default -> log.debug("Ignoring Stripe event {} ({})", event.getId(), event.getType());
        }
    }

    private Event verify(String payload, String signatureHeader) {
        if (!stripeProperties.hasWebhookSecret()) {
            // 503 rather than accepting unverified events; Stripe will retry once the secret is configured.
            throw new PaymentProviderException("Webhook is not configured", HttpStatus.SERVICE_UNAVAILABLE, null);
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new WebhookSignatureException("Missing Stripe-Signature header", null);
        }
        try {
            // Also rejects events whose signed timestamp is more than 5 minutes old (replays).
            return Webhook.constructEvent(payload, signatureHeader, stripeProperties.webhookSecret());
        } catch (SignatureVerificationException e) {
            log.warn("Rejected Stripe webhook with invalid signature: {}", e.getMessage());
            throw new WebhookSignatureException("Invalid Stripe signature", e);
        } catch (RuntimeException e) {
            // Correctly signed but not parseable as an event.
            throw new WebhookSignatureException("Invalid webhook payload", e);
        }
    }

    /**
     * The event's Checkout Session. If the endpoint's API version differs from this SDK's, getObject() is
     * empty; we only read id and payment_status, which are stable across versions, so fall back to the
     * version-tolerant deserializer.
     */
    private static Session session(Event event) {
        var deserializer = event.getDataObjectDeserializer();
        StripeObject object = deserializer.getObject().orElse(null);
        if (object == null) {
            try {
                object = deserializer.deserializeUnsafe();
            } catch (EventDataObjectDeserializationException e) {
                throw new IllegalStateException("Could not read checkout session from event " + event.getId(), e);
            }
        }
        if (!(object instanceof Session session)) {
            throw new IllegalStateException("Event " + event.getId() + " does not contain a checkout session");
        }
        return session;
    }
}
