package com.mealmind.exception;

/** The thing asked for does not exist (or is not visible to the caller). Mapped to HTTP 404 by GlobalExceptionHandler. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
