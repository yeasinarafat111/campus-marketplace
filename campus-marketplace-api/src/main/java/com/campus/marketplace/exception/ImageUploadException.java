package com.campus.marketplace.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** The image host failed or isn't configured. */
@Getter
public class ImageUploadException extends RuntimeException {

    private final HttpStatus status;

    public ImageUploadException(String message, HttpStatus status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }
}
