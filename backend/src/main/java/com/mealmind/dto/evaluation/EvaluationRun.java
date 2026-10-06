package com.mealmind.dto.evaluation;

import java.util.List;
import java.util.Map;

/**
 * One evaluation run in a source-independent shape. The page is written against THIS, never against a file format, so a
 * different source (for example scored real traces) can fill the same structure later.
 *
 * Nothing here assumes three arms, a particular metric, or synthetic data: arms and metrics are lists, and the
 * data's origin is stated by {@code dataKind}.
 *
 * @param schemaVersion shape of this structure; bump it on a breaking change
 * @param source        where the run came from: "offline_golden_set" today
 * @param dataKind      "synthetic", "real" or "unknown". The page must show a warning for anything but "real"
 * @param dataNote      one plain sentence about what the data is and what it cannot show
 * @param metrics       definitions of every metric that appears in {@code arms[].metrics}
 * @param breakdown     per-group counts (here: per case category); may be null
 * @param comparisons   paired comparisons between two arms
 * @param gate          a filter that ran before the arms (here the medical-topic gate); may be null
 * @param caveats       limits of this particular run, each one a full sentence
 */
public record EvaluationRun(
        int schemaVersion,
        String runId,
        String source,
        String dataKind,
        String dataNote,
        RunMeta meta,
        List<MetricDefinition> metrics,
        List<ArmResult> arms,
        Breakdown breakdown,
        List<Comparison> comparisons,
        Gate gate,
        List<String> caveats
) {

    public record RunMeta(String model, Double temperature, Map<String, String> promptVersions, Double spentUsd,
                          Integer cases, Integer repeats, String createdAt) {
    }

    /**
     * @param type "rate" (k out of n, with an interval) or "mean" (one number)
     * @param goal "lower_is_better", "higher_is_better" or "neutral"
     * @param headline true for the few metrics the page leads with
     */
    public record MetricDefinition(String key, String label, String description, String type, String unit, String goal,
                                   boolean headline) {
    }

    /** A rate fills k, n, value and the interval; a mean fills value only. */
    public record MetricValue(Integer k, Integer n, Double value, Double ciLow, Double ciHigh) {
    }

    public record ArmResult(String id, String label, int runs, Map<String, MetricValue> metrics) {
    }

    public record Breakdown(String dimension, List<String> countKeys, List<BreakdownGroup> groups) {
    }

    /** arms: arm id -> "runs" plus one count per entry of countKeys. */
    public record BreakdownGroup(String key, Map<String, Map<String, Integer>> arms) {
    }

    public record Comparison(String left, String right, String metric, int cases, double meanDiff, double ciLow,
                             double ciHigh, int leftHigher, int rightHigher, int equal) {
    }

    public record Gate(String name, List<String> blockedCaseIds, List<String> falsePositives,
                       List<String> falseNegatives) {
    }
}
