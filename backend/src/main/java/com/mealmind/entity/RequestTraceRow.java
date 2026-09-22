package com.mealmind.entity;

import java.time.LocalDateTime;

/**
 * Flat persistence row for diet_request_trace. Written wholesale by insert()
 * (a placeholder until Part 2's TraceScope produces real trace_json), and
 * partially by updateLabel() - which only touches the expected/labeled/
 * label_note columns, leaving the rest of a passed-in row unused.
 */
public class RequestTraceRow {
    private Long id;
    private String traceId;
    private String sessionId;
    private Long userId;
    private String status;
    private Integer eventCount;
    private Long durationMs;
    private String errorMessage;
    private String traceJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String expectedIntent;
    private String expectedSlots;
    private String expectedClarifyAction;
    private Long labeledBy;
    private LocalDateTime labeledAt;
    private String labelNote;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getEventCount() {
        return eventCount;
    }

    public void setEventCount(Integer eventCount) {
        this.eventCount = eventCount;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getTraceJson() {
        return traceJson;
    }

    public void setTraceJson(String traceJson) {
        this.traceJson = traceJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getExpectedIntent() {
        return expectedIntent;
    }

    public void setExpectedIntent(String expectedIntent) {
        this.expectedIntent = expectedIntent;
    }

    public String getExpectedSlots() {
        return expectedSlots;
    }

    public void setExpectedSlots(String expectedSlots) {
        this.expectedSlots = expectedSlots;
    }

    public String getExpectedClarifyAction() {
        return expectedClarifyAction;
    }

    public void setExpectedClarifyAction(String expectedClarifyAction) {
        this.expectedClarifyAction = expectedClarifyAction;
    }

    public Long getLabeledBy() {
        return labeledBy;
    }

    public void setLabeledBy(Long labeledBy) {
        this.labeledBy = labeledBy;
    }

    public LocalDateTime getLabeledAt() {
        return labeledAt;
    }

    public void setLabeledAt(LocalDateTime labeledAt) {
        this.labeledAt = labeledAt;
    }

    public String getLabelNote() {
        return labelNote;
    }

    public void setLabelNote(String labelNote) {
        this.labelNote = labelNote;
    }
}
