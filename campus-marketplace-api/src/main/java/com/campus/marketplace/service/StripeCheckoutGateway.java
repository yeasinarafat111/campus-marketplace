package com.campus.marketplace.service;

import com.campus.marketplace.config.PaymentProperties;
import com.campus.marketplace.config.StripeProperties;
import com.campus.marketplace.exception.PaymentProviderException;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class StripeCheckoutGateway implements CheckoutGateway {

    private final StripeClient stripeClient;
    private final StripeProperties stripeProperties;
    private final PaymentProperties paymentProperties;

    @Override
    public CheckoutSession createCheckoutSession(CheckoutRequest request) {
        if (!stripeProperties.isConfigured()) {
            throw new PaymentProviderException("Payments are not available right now", HttpStatus.SERVICE_UNAVAILABLE, null);
        }
        try {
            Session session = stripeClient.v1().checkout().sessions().create(buildParams(request));
            log.info("Created Stripe Checkout Session {} ({} {})", session.getId(), request.amount(), request.currency());
            return new CheckoutSession(session.getId(), session.getUrl());
        } catch (StripeException e) {
            // Log Stripe's request id so the failure can be looked up in the Stripe dashboard.
            log.error("Stripe checkout session creation failed (request {}): {}", e.getRequestId(), e.getMessage());
            throw new PaymentProviderException("Payment provider error. Please try again.", HttpStatus.BAD_GATEWAY, e);
        }
    }

    @Override
    public void refund(String paymentIntentId, String idempotencyKey) {
        requireConfigured();
        try {
            stripeClient.v1().refunds().create(
                    RefundCreateParams.builder().setPaymentIntent(paymentIntentId).build(),
                    RequestOptions.builder().setIdempotencyKey(idempotencyKey).build());
            log.info("Refunded Stripe PaymentIntent {}", paymentIntentId);
        } catch (StripeException e) {
            if ("charge_already_refunded".equals(e.getCode())) {
                log.info("PaymentIntent {} was already refunded", paymentIntentId);
                return;
            }
            log.error("Stripe refund of {} failed (request {}): {}", paymentIntentId, e.getRequestId(), e.getMessage());
            throw new PaymentProviderException("Refund failed. Please try again.", HttpStatus.BAD_GATEWAY, e);
        }
    }

    @Override
    public String paymentIntentIdOf(String checkoutSessionId) {
        requireConfigured();
        try {
            String paymentIntent = stripeClient.v1().checkout().sessions().retrieve(checkoutSessionId).getPaymentIntent();
            if (paymentIntent == null) {
                throw new PaymentProviderException("Payment provider error. Please try again.", HttpStatus.BAD_GATEWAY, null);
            }
            return paymentIntent;
        } catch (StripeException e) {
            log.error("Stripe session lookup {} failed (request {}): {}", checkoutSessionId, e.getRequestId(), e.getMessage());
            throw new PaymentProviderException("Payment provider error. Please try again.", HttpStatus.BAD_GATEWAY, e);
        }
    }

    private void requireConfigured() {
        if (!stripeProperties.isConfigured()) {
            throw new PaymentProviderException("Payments are not available right now", HttpStatus.SERVICE_UNAVAILABLE, null);
        }
    }

    SessionCreateParams buildParams(CheckoutRequest request) {
        return SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(paymentProperties.successUrl())
                .setCancelUrl(paymentProperties.cancelUrl())
                .setCustomerEmail(request.customerEmail())
                .putAllMetadata(request.metadata())
                .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                        .putAllMetadata(request.metadata())
                        .build())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(request.currency())
                                .setUnitAmount(request.amount())
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName(request.productName())
                                        .build())
                                .build())
                        .build())
                .build();
    }
}
