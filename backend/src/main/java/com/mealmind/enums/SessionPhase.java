package com.mealmind.enums;

/**
 * Stage of a multi-turn conversation. Orchestrator (Part 2) uses this to
 * decide how to interpret the next turn's input.
 */
public enum SessionPhase {
    /** Session just created, no business flow entered yet. */
    START,

    /** System is asking the user a follow-up question for missing slots. */
    CLARIFY,

    /** System has produced a recommendation for the current slots. */
    RECOMMEND,

    /** System is working through a multi-meal plan. */
    PLAN
}
