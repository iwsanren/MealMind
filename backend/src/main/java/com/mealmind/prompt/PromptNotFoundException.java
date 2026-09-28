package com.mealmind.prompt;

/**
 * Thrown when a prompt scenario has no configured version, or the
 * corresponding .txt file is missing from the classpath.
 */
public class PromptNotFoundException extends RuntimeException {

    public PromptNotFoundException(String message) {
        super(message);
    }

    public PromptNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
