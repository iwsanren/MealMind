package com.mealmind.output;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Turns raw LLM text into a validated object, or a list of field errors.
 * Two stages: Jackson (JSON syntax, types, enum values, coercion), then Bean
 * Validation (required fields, blanks, empty lists). Never throws on bad LLM
 * output — the caller decides whether to retry or give up.
 */
@Component
public class OutputParser {

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public OutputParser(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public <T> ParseResult<T> parse(String rawJson, Class<T> type) {
        T value;
        try {
            value = objectMapper.readValue(rawJson, type);
        } catch (JsonMappingException e) {
            return ParseResult.failed(List.of(new FieldError(pathOf(e), simplify(e))));
        } catch (JsonProcessingException e) {
            return ParseResult.failed(List.of(new FieldError("$", "Not valid JSON: " + e.getOriginalMessage())));
        }

        if (value == null) {
            return ParseResult.failed(List.of(new FieldError("$", "Output is empty (null)")));
        }

        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (violations.isEmpty()) {
            return ParseResult.ok(value);
        }
        List<FieldError> errors = new ArrayList<>();
        violations.stream()
                .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                .forEach(v -> errors.add(new FieldError(v.getPropertyPath().toString(), v.getMessage())));
        return ParseResult.failed(errors);
    }

    // Builds "claims[0].source" from Jackson's reference chain.
    private static String pathOf(JsonMappingException e) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference ref : e.getPath()) {
            if (ref.getFieldName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? "$" : path.toString();
    }

    // getOriginalMessage() keeps Jackson's wording without its long location/reference suffix.
    private static String simplify(JsonMappingException e) {
        return e.getOriginalMessage();
    }
}
