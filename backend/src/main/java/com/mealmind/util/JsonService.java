package com.mealmind.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mealmind.exception.MealException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Converts between List<String> and its JSON-array text form, used when
 * persisting tag columns. Null / blank input maps to an empty list;
 * any Jackson failure is rethrown as a MealException.
 */
@Service
public class JsonService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    // Reuse Spring Boot's auto-configured, shared ObjectMapper.
    public JsonService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Serialize to a JSON array string; null becomes "[]". */
    public String toJsonArray(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            throw new MealException("Failed to serialize list to JSON", e);
        }
    }

    /** Parse a JSON array string back to a list; null / "" becomes an empty list. */
    public List<String> fromJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception e) {
            throw new MealException("Failed to parse JSON array", e);
        }
    }
}