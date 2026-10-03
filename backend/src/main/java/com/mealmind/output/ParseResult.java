package com.mealmind.output;

import java.util.List;

/** Either a valid parsed value, or the list of field errors explaining why not. */
public record ParseResult<T>(T value, List<FieldError> errors) {

    public static <T> ParseResult<T> ok(T value) {
        return new ParseResult<>(value, List.of());
    }

    public static <T> ParseResult<T> failed(List<FieldError> errors) {
        return new ParseResult<>(null, List.copyOf(errors));
    }

    public boolean isValid() {
        return errors.isEmpty();
    }
}
