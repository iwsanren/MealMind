package com.mealmind.model;

import java.util.List;

/**
 * Result of the final compliance scan run over user input / a draft reply
 * before it goes out. When blocked, the caller (the future Orchestrator)
 * swaps the draft reply for conservativeMessage instead of sending it as-is.
 */
public record RiskGuardResult(
        boolean blocked,
        List<String> reasons,
        String conservativeMessage
) {

    // Defensive copy so a caller's mutable list can't change the result after the fact.
    public RiskGuardResult {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /** No risk keyword matched; the original reply may go out unchanged. */
    public static RiskGuardResult pass() {
        return new RiskGuardResult(false, List.of(), null);
    }

    /** At least one risk rule matched; conservativeMessage should replace the reply. */
    public static RiskGuardResult block(List<String> reasons, String conservativeMessage) {
        return new RiskGuardResult(true, reasons, conservativeMessage);
    }
}
