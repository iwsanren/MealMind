package com.mealmind.dto.feedback;

/**
 * Inbound payload for POST /api/v1/diet/feedback. action is a free string
 * (like/dislike/accept/ignore/...) defined by the frontend; the backend only
 * checks it's non-blank.
 */
public record FeedbackRequest(
        String sessionId,
        Long itemId,
        String action,
        Integer rating,
        String reason
) {
}
