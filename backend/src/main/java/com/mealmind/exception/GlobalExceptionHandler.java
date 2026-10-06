package com.mealmind.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /** A query or path parameter of the wrong type (e.g. limit=abc) is the caller's mistake -> 400, not a server error. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadParameter(MethodArgumentTypeMismatchException e) {
        return Map.of("message", "Invalid value for parameter '" + e.getName() + "'");
    }

    /** Unknown resource -> 404. */
    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNotFound(NotFoundException e) {
        return Map.of("message", e.getMessage());
    }

    /** A URL that no controller serves is a 404, not the 500 the catch-all below would turn it into. */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleUnknownUrl(NoResourceFoundException e) {
        return Map.of("message", "Not found");
    }

    /** Anything else -> 500, with a safe fallback message (Map.of rejects null). */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> handleException(Exception e) {
        String msg = e.getMessage() == null ? "Internal server error" : e.getMessage();
        return Map.of("message", msg);
    }
}