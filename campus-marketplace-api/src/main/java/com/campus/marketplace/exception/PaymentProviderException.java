package com.campus.marketplace.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Stripe failed or isn't configured. */
@Getter
public class PaymentProviderException extends RuntimeException {

    private final HttpStatus status;

    public PaymentProviderException(String message, HttpStatus status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }
}
