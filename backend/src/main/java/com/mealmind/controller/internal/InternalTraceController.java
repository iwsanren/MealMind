package com.mealmind.controller.internal;

import com.mealmind.dto.internal.TraceWriteRequest;
import com.mealmind.dto.internal.TraceWriteResponse;
import com.mealmind.entity.RequestTraceRow;
import com.mealmind.exception.MealException;
import com.mealmind.service.trace.AgentTraceService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal API for ai-service: persist one agent run into diet_request_trace. */
@RestController
@RequestMapping("/internal/v1/traces")
public class InternalTraceController {

    // Column widths of diet_request_trace; checked here so oversize input is a 400, not a database 500.
    private static final int MAX_TRACE_ID = 128;
    private static final int MAX_SESSION_ID = 64;
    private static final int MAX_STATUS = 32;

    private final AgentTraceService agentTraceService;

    public InternalTraceController(AgentTraceService agentTraceService) {
        this.agentTraceService = agentTraceService;
    }

    @PostMapping
    public TraceWriteResponse write(@RequestBody TraceWriteRequest request) {
        if (request == null) {
            throw new MealException("Request body is required");
        }
        requireText(request.traceId(), "traceId", MAX_TRACE_ID);
        requireText(request.sessionId(), "sessionId", MAX_SESSION_ID);
        requireText(request.status(), "status", MAX_STATUS);
        if (request.userId() == null) {
            throw new MealException("userId is required");
        }
        if (request.durationMs() != null && request.durationMs() < 0) {
            throw new MealException("durationMs must not be negative");
        }
        if (request.events() == null || !request.events().isArray()) {
            throw new MealException("events is required and must be a JSON array");
        }

        RequestTraceRow row = new RequestTraceRow();
        row.setTraceId(request.traceId().trim());
        row.setSessionId(request.sessionId().trim());
        row.setUserId(request.userId());
        row.setStatus(request.status().trim());
        row.setEventCount(request.events().size());
        row.setDurationMs(request.durationMs());
        row.setErrorMessage(request.errorMessage());
        row.setTraceJson(request.events().toString()); // valid JSON by construction
        try {
            agentTraceService.insert(row);
        } catch (DuplicateKeyException e) {
            throw new MealException("traceId already exists: " + row.getTraceId(), e);
        }
        return new TraceWriteResponse(row.getTraceId(), row.getEventCount());
    }

    private static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new MealException(field + " is required");
        }
        if (value.trim().length() > maxLength) {
            throw new MealException(field + " must be at most " + maxLength + " characters");
        }
    }
}
