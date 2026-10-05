package com.campus.marketplace.service;

import com.campus.marketplace.config.PaymentProperties;
import com.campus.marketplace.config.StripeProperties;
import com.campus.marketplace.exception.PaymentProviderException;
import com.stripe.StripeClient;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class StripeCheckoutGatewayTest {

    private static final PaymentProperties PAYMENTS = new PaymentProperties("usd",
            new PaymentProperties.Fee(new java.math.BigDecimal("20.00"), new java.math.BigDecimal("0.03"),
                    new java.math.BigDecimal("0.05"), 50),
            "http://localhost:3000/payments/success?session_id={CHECKOUT_SESSION_ID}",
            "http://localhost:3000/payments/cancel", java.time.Duration.ofDays(7), java.time.Duration.ofHours(48));

    private static final CheckoutGateway.CheckoutRequest REQUEST = new CheckoutGateway.CheckoutRequest(
            500, "usd", "Reservation deposit: Lamp", "buyer@seu.edu.bd",
            Map.of("user_id", "7", "listing_id", "42", "purpose", "RESERVE_ITEM"));

    @Test
    void buildParams_paymentModeWithOneFixedPriceLineItem() {
        var gateway = new StripeCheckoutGateway(mock(StripeClient.class), new StripeProperties("sk_test_x", null), PAYMENTS);

        SessionCreateParams params = gateway.buildParams(REQUEST);

        assertThat(params.getMode()).isEqualTo(SessionCreateParams.Mode.PAYMENT);
        assertThat(params.getSuccessUrl()).isEqualTo("http://localhost:3000/payments/success?session_id={CHECKOUT_SESSION_ID}");
        assertThat(params.getCancelUrl()).isEqualTo("http://localhost:3000/payments/cancel");
        assertThat(params.getCustomerEmail()).isEqualTo("buyer@seu.edu.bd");
        assertThat(params.getMetadata()).containsEntry("listing_id", "42").containsEntry("purpose", "RESERVE_ITEM");
        assertThat(params.getPaymentIntentData().getMetadata()).containsEntry("user_id", "7");

        assertThat(params.getLineItems()).singleElement().satisfies(item -> {
            assertThat(item.getQuantity()).isEqualTo(1L);
            assertThat(item.getPriceData().getCurrency()).isEqualTo("usd");
            assertThat(item.getPriceData().getUnitAmount()).isEqualTo(500L);
            assertThat(item.getPriceData().getProductData().getName()).isEqualTo("Reservation deposit: Lamp");
        });
    }

    @Test
    void missingSecretKey_returns503WithoutCallingStripe() {
        StripeClient client = mock(StripeClient.class);
        var gateway = new StripeCheckoutGateway(client, new StripeProperties("", null), PAYMENTS);

        assertThatThrownBy(() -> gateway.createCheckoutSession(REQUEST))
                .isInstanceOfSatisfying(PaymentProviderException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(client);
    }

    @Test
    void stripeProperties_detectsLiveMode() {
        assertThat(new StripeProperties("sk_live_abc", null).isLiveMode()).isTrue();
        assertThat(new StripeProperties("sk_test_abc", null).isLiveMode()).isFalse();
        assertThat(new StripeProperties(null, null).isConfigured()).isFalse();
    }
}
