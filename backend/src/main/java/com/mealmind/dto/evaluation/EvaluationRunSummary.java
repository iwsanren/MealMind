package com.mealmind.dto.evaluation;

/** One line of the run picker. Only complete runs are listed; a run that failed at the API level is never offered. */
public record EvaluationRunSummary(
        String runId,
        String source,
        String dataKind,
        String title,
        String createdAt,
        String model,
        Integer cases,
        Integer repeats
) {
}
