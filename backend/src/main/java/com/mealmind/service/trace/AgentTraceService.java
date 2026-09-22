package com.mealmind.service.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mealmind.dto.trace.TraceLabelRequest;
import com.mealmind.entity.RequestTraceRow;
import com.mealmind.exception.MealException;
import com.mealmind.mapper.AgentTraceMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Storage/query side of agent request tracing: write a trace row, look it up
 * by id/session/time range, and record a human label on it. Recording a real
 * trace automatically (open/record/close around an actual agent call) is
 * Part 2's job once the agent-call library is in place; insert() here is a
 * plain passthrough used to seed rows until then.
 */
@Service
public class AgentTraceService {

    /** Default rows returned when the caller doesn't specify a limit. */
    private static final int DEFAULT_LIMIT = 200;

    /** Upper bound a caller-supplied limit gets clamped to. */
    private static final int MAX_LIMIT = 1000;

    private final AgentTraceMapper agentTraceMapper;
    private final ObjectMapper objectMapper;

    // expectedSlots is a whole SlotBundle object serialized as one JSON blob,
    // not the JSON-array shape JsonService handles, so this injects Jackson
    // directly instead of routing through JsonService.
    public AgentTraceService(AgentTraceMapper agentTraceMapper, ObjectMapper objectMapper) {
        this.agentTraceMapper = agentTraceMapper;
        this.objectMapper = objectMapper;
    }

    /** Placeholder write path until Part 2's TraceScope produces real rows. */
    public int insert(RequestTraceRow row) {
        return agentTraceMapper.insert(row);
    }

    public RequestTraceRow findByTraceId(Long userId, String traceId) {
        return agentTraceMapper.findByTraceId(userId, traceId);
    }

    /** Most recent traces for one session, newest first. */
    public List<RequestTraceRow> findBySessionId(Long userId, String sessionId, Integer limit) {
        return agentTraceMapper.findBySessionId(userId, sessionId, normalizeLimit(limit));
    }

    /** Traces in [startAt, endAt), optionally restricted to unlabeled ones. */
    public List<RequestTraceRow> findByTimeRange(Long userId, LocalDateTime startAt, LocalDateTime endAt,
                                                  Boolean onlyUnlabeled, Integer limit) {
        if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
            throw new MealException("Trace query time range is invalid");
        }
        return agentTraceMapper.findByTimeRange(
                userId, startAt, endAt, Boolean.TRUE.equals(onlyUnlabeled), normalizeLimit(limit));
    }

    /** Records a human-supplied "correct answer" for one trace. */
    public void updateLabel(Long userId, String traceId, TraceLabelRequest request) {
        if (traceId == null || traceId.isBlank()) {
            throw new MealException("traceId must not be blank");
        }
        if (request == null) {
            throw new MealException("Label content must not be null");
        }
        RequestTraceRow row = new RequestTraceRow();
        row.setUserId(userId);
        row.setTraceId(traceId);
        row.setExpectedIntent(request.expectedIntent());
        row.setExpectedSlots(request.expectedSlots() == null ? null : toJson(request.expectedSlots()));
        row.setExpectedClarifyAction(request.expectedClarifyAction());
        row.setLabeledBy(userId);
        row.setLabelNote(request.labelNote());

        int updated = agentTraceMapper.updateLabel(row);
        if (updated == 0) {
            throw new MealException("Trace not found or not owned by this user");
        }
    }

    private int normalizeLimit(Integer limit) {
        return limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new MealException("Failed to serialize trace label to JSON", e);
        }
    }
}
