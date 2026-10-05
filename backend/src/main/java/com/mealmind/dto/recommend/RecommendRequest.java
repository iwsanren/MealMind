package com.mealmind.dto.recommend;

/** Body of POST /api/v1/sessions/{sessionId}/recommend: the user's own words for this turn. */
public record RecommendRequest(String message) {
}
