package com.mealmind.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Translates exceptions thrown by any @RestController under com.mealmind
 * into a uniform JSON body: {"message": "..."}.
 * NOTE: no basePackages filter here - it must apply globally.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Business/validation errors -> 400. */
    @ExceptionHandler(MealException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleMealException(MealException e) {
        return Map.of("message", e.getMessage());
    }

    /** Anything else -> 500, with a safe fallback message (Map.of rejects null). */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> handleException(Exception e) {
        String msg = e.getMessage() == null ? "Internal server error" : e.getMessage();
        return Map.of("message", msg);
    }
}