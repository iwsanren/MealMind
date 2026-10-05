package com.mealmind.dto.recommend;

import com.mealmind.dto.meal.MealResponse;

import java.util.List;

/**
 * What the chat page shows for one turn.
 *
 * @param source         who produced the text: AGENT (verified answer from ai-service), RULES (the rule-based
 *                       recommender), RISK_GUARD (the message touched a medical topic, fixed conservative reply)
 * @param fallbackReason why RULES was used instead of the agent; null when source is AGENT or RISK_GUARD.
 *                       AGENT_DISABLED / DISABLED_BY_REQUEST are choices; AGENT_TIMEOUT, AGENT_UNREACHABLE,
 *                       AGENT_HTTP_xxx, AGENT_BAD_RESPONSE, AGENT_&lt;status&gt;, AGENT_UNKNOWN_MEAL and
 *                       AGENT_FAILED_BACKEND_CHECK are failures
 * @param traceId        id of the stored agent trace (readable at /api/v1/debug/traces/{traceId}); null for RULES
 */
public record RecommendResponse(
        String sessionId,
        Source source,
        String fallbackReason,
        String text,
        List<MealResponse> meals,
        String traceId
) {
    public enum Source {AGENT, RULES, RISK_GUARD}
}
