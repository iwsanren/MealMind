package com.mealmind.entity;

import java.time.LocalDateTime;

/**
 * Flat persistence row for session_state. phase/slots/last_recommendations
 * stay as raw text here; parsing into a SessionState happens in the service.
 * Unlike MealItemRow, id is a business-generated String, not an auto-increment
 * Long - it's minted by the service before the first INSERT.
 */
public class SessionRow {
    private String id;
    private Long userId;
    private String phase;
    private String slots;                 // JSON object: 7 dimensions + _meta
    private String lastRecommendations;   // JSON array of meal ids
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getSlots() {
        return slots;
    }

    public void setSlots(String slots) {
        this.slots = slots;
    }

    public String getLastRecommendations() {
        return lastRecommendations;
    }

    public void setLastRecommendations(String lastRecommendations) {
        this.lastRecommendations = lastRecommendations;
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
}
