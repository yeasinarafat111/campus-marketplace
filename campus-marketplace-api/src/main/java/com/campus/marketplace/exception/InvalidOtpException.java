package com.campus.marketplace.exception;

/** A rejected verification code (wrong, expired, used, or locked). Returned as 400. */
public class InvalidOtpException extends BadRequestException {

    public InvalidOtpException(String message) {
        super(message);
    }
}
