package com.mealmind.dto.internal;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Body of POST /internal/v1/traces. events must be a JSON array (one entry per
 * recorded step); it is stored as trace_json and its length becomes event_count.
 */
public record TraceWriteRequest(
        String traceId,
        String sessionId,
        Long userId,
        String status,
        Long durationMs,
        String errorMessage,
        JsonNode events
) {
}
