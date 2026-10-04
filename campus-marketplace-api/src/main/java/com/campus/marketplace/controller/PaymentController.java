package com.campus.marketplace.controller;

import com.campus.marketplace.dto.CheckoutSessionRequest;
import com.campus.marketplace.dto.CheckoutSessionResponse;
import com.campus.marketplace.service.PaymentService;
import com.campus.marketplace.service.StripeWebhookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * checkout-session requires a JWT. The webhook is public (SecurityConfig) and authenticated by Stripe's
 * signature instead.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final StripeWebhookService webhookService;

    /** Returns the Stripe Checkout URL to redirect the user to. The amount is fixed server-side by purpose. */
    @PostMapping("/checkout-session")
    @ResponseStatus(HttpStatus.CREATED)
    public CheckoutSessionResponse createCheckoutSession(@Valid @RequestBody CheckoutSessionRequest request) {
        return paymentService.createCheckoutSession(request);
    }

    /**
     * Stripe webhook. The body must reach the signature check byte-for-byte, so it's taken as a raw String.
     * Returns 200 once the event is verified and handled (or deliberately ignored); 400 for a bad signature;
     * anything else makes Stripe retry.
     */
    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(@RequestBody String payload,
                                        @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
        webhookService.handle(payload, signature);
        return ResponseEntity.ok().build();
    }
}
