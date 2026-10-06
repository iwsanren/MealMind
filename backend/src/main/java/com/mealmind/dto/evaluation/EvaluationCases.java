package com.mealmind.dto.evaluation;

import java.util.List;
import java.util.Map;

/** The case list and the case detail of one run. Kept apart from EvaluationRun because runs can be large. */
public final class EvaluationCases {

    private EvaluationCases() {
    }

    /** The user's stated limits in the case (the TRUE constraints every arm is scored against). */
    public record Constraints(Double maxPrice, List<String> excludeAllergens, boolean requiresHighProtein,
                              Double minProteinG, String mealTime) {
    }

    public record ArmCounts(int runs, int unsafe, int correctOutcome) {
    }

    /**
     * @param feasibleExists whether a meal satisfying every constraint exists (false = "nothing fits" is the right answer)
     */
    public record CaseRow(String caseId, String category, String userMessage, Constraints constraints,
                          Boolean feasibleExists, Map<String, ArmCounts> arms) {
    }

    public record CasePage(int total, int limit, int offset, List<String> categories, List<CaseRow> items) {
    }

    public record Recommendation(Long mealId, String mealName, String reason, String noMatchReason) {
    }

    public record TimelineStep(String type, Integer round, String label) {
    }

    /**
     * One repeat of one case in one arm.
     *
     * @param shown          false when the answer was withheld (failed verification); then there is no recommendation
     * @param traceId        id of a stored trace, or null (offline runs carry their events inline)
     * @param timeline       what the agent did step by step; empty for arms without tools
     */
    public record Repeat(int repeat, String status, boolean shown, boolean unsafe, boolean correctOutcome,
                         boolean hardViolation, boolean claimFail, boolean hallucinatedDish, boolean falseNoMatch,
                         List<String> issues, Recommendation recommendation, int llmCalls, double costUsd,
                         String traceId, List<TimelineStep> timeline) {
    }

    public record ArmCase(String id, String label, List<Repeat> repeats) {
    }

    public record CaseDetail(String caseId, String category, String userMessage, Constraints constraints,
                             Boolean feasibleExists, List<ArmCase> arms) {
    }
}
