package com.mealmind.output;

/** One validation problem: which field (JSON path, e.g. "claims[0].source") and what is wrong. */
public record FieldError(String path, String message) {
}
