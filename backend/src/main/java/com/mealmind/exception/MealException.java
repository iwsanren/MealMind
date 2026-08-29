package com.mealmind.exception;

/**
 * Distinguish between “expected business errors” and “program bugs/system failures.”
 */
public class MealException extends RuntimeException {

    public MealException(String message) {
        super(message);
    }

    public MealException(String message, Throwable cause) {
        super(message, cause);
    }
}
