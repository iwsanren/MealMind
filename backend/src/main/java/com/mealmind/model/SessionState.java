package com.mealmind.model;

import com.mealmind.enums.SessionPhase;
import com.mealmind.enums.SourceMode;

import java.util.List;

/**
 * Multi-turn conversation state: what phase the conversation is in, the
 * slots accumulated so far, and the meal ids recommended last round.
 * SessionStateService is the only reader/writer; nothing else touches
 * session_state directly.
 */
public record SessionState(
        String sessionId,
        Long userId,
        SessionPhase phase,
        SourceMode sourceMode,
        SlotBundle slots,
        List<Long> lastRecommendations
) {

    /** A brand-new session: START phase, nothing accumulated yet. */
    public static SessionState fresh(String sessionId, Long userId, SourceMode sourceMode) {
        return new SessionState(sessionId, userId, SessionPhase.START, sourceMode, SlotBundle.empty(), List.of());
    }
}
