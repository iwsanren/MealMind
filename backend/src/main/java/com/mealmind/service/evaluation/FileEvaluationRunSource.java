package com.mealmind.service.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mealmind.config.EvaluationProperties;
import com.mealmind.dto.evaluation.EvaluationCases;
import com.mealmind.dto.evaluation.EvaluationCases.ArmCase;
import com.mealmind.dto.evaluation.EvaluationCases.ArmCounts;
import com.mealmind.dto.evaluation.EvaluationCases.CaseDetail;
import com.mealmind.dto.evaluation.EvaluationCases.CasePage;
import com.mealmind.dto.evaluation.EvaluationCases.CaseRow;
import com.mealmind.dto.evaluation.EvaluationCases.Constraints;
import com.mealmind.dto.evaluation.EvaluationCases.Recommendation;
import com.mealmind.dto.evaluation.EvaluationCases.Repeat;
import com.mealmind.dto.evaluation.EvaluationCases.TimelineStep;
import com.mealmind.dto.evaluation.EvaluationRun;
import com.mealmind.dto.evaluation.EvaluationRun.ArmResult;
import com.mealmind.dto.evaluation.EvaluationRun.Breakdown;
import com.mealmind.dto.evaluation.EvaluationRun.BreakdownGroup;
import com.mealmind.dto.evaluation.EvaluationRun.Comparison;
import com.mealmind.dto.evaluation.EvaluationRun.Gate;
import com.mealmind.dto.evaluation.EvaluationRun.MetricDefinition;
import com.mealmind.dto.evaluation.EvaluationRun.MetricValue;
import com.mealmind.dto.evaluation.EvaluationRun.RunMeta;
import com.mealmind.dto.evaluation.EvaluationRunSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Reads the output of evaluation/run_eval.py: one folder per run (named by its UTC timestamp) holding summary.json and
 * runs.jsonl, plus the case definitions in cases.json.
 *
 * Safety: a run id is only ever used after it matches the exact timestamp pattern, and every path is checked to stay
 * inside the configured root (symbolic links included) before it is opened. A run folder with INCOMPLETE.txt (some runs
 * failed at the API level) is treated as not existing, because its numbers are not valid observations.
 */
@Component
public class FileEvaluationRunSource implements EvaluationRunSource {

    private static final Logger log = LoggerFactory.getLogger(FileEvaluationRunSource.class);

    static final String SOURCE = "offline_golden_set";
    static final Pattern RUN_ID = Pattern.compile("^\\d{8}-\\d{6}$");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final long MAX_FILE_BYTES = 25L * 1024 * 1024;

    private static final List<MetricDefinition> DEFINITIONS = List.of(
            new MetricDefinition("unsafe", "Unsafe answer",
                    "A shown answer that breaks a hard constraint, makes a claim the code check rejects, or invents a dish.",
                    "rate", "%", "lower_is_better", true),
            new MetricDefinition("correct_outcome", "Correct outcome",
                    "Safe, and the right kind of answer: a feasible meal when one exists, \"nothing fits\" when none does.",
                    "rate", "%", "higher_is_better", true),
            new MetricDefinition("false_no_match", "Said \"nothing fits\" when something did",
                    "The answer refused although a meal satisfying every constraint existed (over-refusal).",
                    "rate", "%", "lower_is_better", true),
            new MetricDefinition("hard_violation", "Hard-constraint violation",
                    "Recommended a meal that breaks the budget, an allergen limit or a protein need (unknown facts never satisfy a limit).",
                    "rate", "%", "lower_is_better", false),
            new MetricDefinition("claim_fail", "Unsupported claim",
                    "A factual claim in the answer that does not match the meal's data.",
                    "rate", "%", "lower_is_better", false),
            new MetricDefinition("hallucinated_dish", "Invented dish",
                    "Recommended a meal that is not in the library.",
                    "rate", "%", "lower_is_better", false),
            new MetricDefinition("no_answer", "No answer shown",
                    "The answer was withheld because it never passed verification, or was never produced.",
                    "rate", "%", "neutral", false),
            new MetricDefinition("disliked_shown", "Recommended a disliked meal",
                    "Recommended a meal the user had marked as disliked (a soft preference).",
                    "rate", "%", "lower_is_better", false),
            new MetricDefinition("off_meal_time_of_recommended", "Off the requested meal time",
                    "Of the runs that recommended a meal: its meal-time tags did not match the request (a soft preference).",
                    "rate", "%", "lower_is_better", false),
            new MetricDefinition("avg_llm_calls", "Model calls per run", "Average number of model calls in one run.",
                    "mean", "calls", "neutral", false),
            new MetricDefinition("avg_cost_usd", "Cost per run", "Average model cost of one run (model calls only).",
                    "mean", "USD", "lower_is_better", false)
    );

    private record CachedRows(long modified, long size, List<JsonNode> rows) {
    }

    private record CachedCases(long modified, long size, Map<String, JsonNode> cases) {
    }

    private final Path root;
    private final Path casesFile;
    private final ObjectMapper mapper;
    private final Map<String, CachedRows> rowsCache = new ConcurrentHashMap<>();
    private volatile CachedCases casesCache;

    public FileEvaluationRunSource(EvaluationProperties properties, ObjectMapper mapper) {
        this.root = Path.of(properties.resultsDir()).toAbsolutePath().normalize();
        this.casesFile = Path.of(properties.casesFile()).toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    @Override
    public boolean supports(String runId) {
        return runId != null && RUN_ID.matcher(runId).matches();
    }

    // ---- listing ----

    @Override
    public List<EvaluationRunSummary> listRuns() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (supports(name)) {
                    ids.add(name);
                }
            }
        } catch (IOException e) {
            log.warn("could not list the evaluation results folder ({})", e.getClass().getSimpleName());
            return List.of();
        }
        ids.sort(Comparator.reverseOrder());
        List<EvaluationRunSummary> runs = new ArrayList<>();
        for (String id : ids) {
            readSummary(id).ifPresent(summary -> runs.add(toListItem(id, summary)));
        }
        return runs;
    }

    private EvaluationRunSummary toListItem(String runId, JsonNode summary) {
        JsonNode meta = summary.path("meta");
        String model = text(meta, "model");
        Integer cases = integer(meta, "n_cases");
        Integer repeats = integer(meta, "repeats");
        return new EvaluationRunSummary(runId, SOURCE, dataKind(meta),
                String.format("%s · %s cases × %s repeats", model == null ? "unknown model" : model,
                        cases == null ? "?" : cases, repeats == null ? "?" : repeats),
                createdAt(runId), model, cases, repeats);
    }

    // ---- one run ----

    @Override
    public Optional<EvaluationRun> findRun(String runId) {
        return readSummary(runId).map(summary -> toRun(runId, summary));
    }

    private EvaluationRun toRun(String runId, JsonNode file) {
        JsonNode meta = file.path("meta");
        JsonNode summary = file.path("summary");
        String kind = dataKind(meta);

        List<ArmResult> arms = new ArrayList<>();
        Set<String> metricKeysSeen = new LinkedHashSet<>();
        for (var armEntry : iterable(summary.path("arms").fields())) {
            JsonNode arm = armEntry.getValue();
            Map<String, MetricValue> values = new LinkedHashMap<>();
            for (MetricDefinition definition : DEFINITIONS) {
                JsonNode node = arm.path(definition.key());
                if ("rate".equals(definition.type()) && node.isObject() && node.has("k")) {
                    JsonNode ci = node.path("ci95");
                    values.put(definition.key(), new MetricValue(node.path("k").asInt(), node.path("n").asInt(),
                            node.path("rate").asDouble(), ci.path(0).asDouble(), ci.path(1).asDouble()));
                } else if ("mean".equals(definition.type()) && node.isNumber()) {
                    values.put(definition.key(), new MetricValue(null, null, node.asDouble(), null, null));
                }
            }
            metricKeysSeen.addAll(values.keySet());
            arms.add(new ArmResult(armEntry.getKey(), text(arm, "name") == null ? armEntry.getKey() : text(arm, "name"),
                    arm.path("runs").asInt(), values));
        }
        List<MetricDefinition> definitions = DEFINITIONS.stream().filter(d -> metricKeysSeen.contains(d.key())).toList();

        Map<String, String> prompts = new LinkedHashMap<>();
        putIfPresent(prompts, "agent", text(meta, "agent_prompt"));
        putIfPresent(prompts, "baseline", text(meta, "baseline_prompt"));
        RunMeta runMeta = new RunMeta(text(meta, "model"), meta.path("temperature").isNumber() ? meta.path("temperature").asDouble() : null,
                prompts, meta.path("spent_usd").isNumber() ? meta.path("spent_usd").asDouble() : null,
                integer(meta, "n_cases"), integer(meta, "repeats"), createdAt(runId));

        return new EvaluationRun(1, runId, SOURCE, kind, dataNote(kind), runMeta, definitions, arms,
                breakdown(summary.path("by_category"), arms), comparisons(summary.path("paired")), gate(file.path("guard")),
                caveats());
    }

    private Breakdown breakdown(JsonNode byCategory, List<ArmResult> arms) {
        if (!byCategory.isObject() || byCategory.isEmpty()) {
            return null;
        }
        Set<String> categories = new TreeSet<>();
        for (var armEntry : iterable(byCategory.fields())) {
            armEntry.getValue().fieldNames().forEachRemaining(categories::add);
        }
        List<String> countKeys = List.of("unsafe", "correct_outcome");
        List<BreakdownGroup> groups = new ArrayList<>();
        for (String category : categories) {
            Map<String, Map<String, Integer>> perArm = new LinkedHashMap<>();
            for (ArmResult arm : arms) {
                JsonNode cell = byCategory.path(arm.id()).path(category);
                if (cell.isObject()) {
                    Map<String, Integer> counts = new LinkedHashMap<>();
                    counts.put("runs", cell.path("runs").asInt());
                    for (String key : countKeys) {
                        counts.put(key, cell.path(key).asInt());
                    }
                    perArm.put(arm.id(), counts);
                }
            }
            groups.add(new BreakdownGroup(category, perArm));
        }
        return new Breakdown("case category", countKeys, groups);
    }

    private List<Comparison> comparisons(JsonNode paired) {
        List<Comparison> list = new ArrayList<>();
        for (var entry : iterable(paired.fields())) {
            // keys look like "A-C:unsafe": left arm, right arm, metric
            String[] parts = entry.getKey().split("[-:]", 3);
            if (parts.length != 3) {
                continue;
            }
            JsonNode v = entry.getValue();
            list.add(new Comparison(parts[0], parts[1], parts[2], v.path("cases").asInt(), v.path("mean_diff").asDouble(),
                    v.path("bootstrap_ci95").path(0).asDouble(), v.path("bootstrap_ci95").path(1).asDouble(),
                    v.path("left_higher").asInt(), v.path("right_higher").asInt(), v.path("equal").asInt()));
        }
        return list;
    }

    private Gate gate(JsonNode guard) {
        if (!guard.isObject()) {
            return null;
        }
        return new Gate("Medical-topic gate (RiskGuard)", strings(guard.path("blocked_case_ids")),
                strings(guard.path("false_positives")), strings(guard.path("false_negatives")));
    }

    private static String dataKind(JsonNode meta) {
        String data = text(meta, "data");
        return data != null && data.toLowerCase().startsWith("synthetic") ? "synthetic" : "unknown";
    }

    private static String dataNote(String kind) {
        return "synthetic".equals(kind)
                ? "All data is synthetic: the dishes and the cases were written to contain edge cases. The numbers say how "
                  + "each arm handles those cases, not how accurate any arm is on real menus."
                : "The run does not state where its data comes from; do not read these numbers as real-world accuracy.";
    }

    private static List<String> caveats() {
        return List.of(
                "Repeats of one case are not independent, so the intervals are a guide, not exact.",
                "This is one run, not a replicated experiment; a second run would give close but not identical numbers.",
                "Latency is not shown: waiting caused by API rate limiting is included in the measured durations.");
    }

    // ---- cases ----

    @Override
    public Optional<CasePage> listCases(String runId, String category, int limit, int offset) {
        Optional<JsonNode> summary = readSummary(runId);
        if (summary.isEmpty()) {
            return Optional.empty();
        }
        List<JsonNode> rows = readRows(runId);
        Map<String, JsonNode> definitions = readCases();
        Map<String, List<JsonNode>> byCase = groupByCase(rows, definitions);

        List<CaseRow> all = new ArrayList<>();
        Set<String> categories = new TreeSet<>();
        for (var entry : byCase.entrySet()) {
            JsonNode first = entry.getValue().get(0);
            String caseCategory = categoryOf(first, definitions.get(entry.getKey()));
            categories.add(caseCategory);
            if (category != null && !category.equals(caseCategory)) {
                continue;
            }
            all.add(new CaseRow(entry.getKey(), caseCategory, userMessage(definitions.get(entry.getKey())),
                    constraints(definitions.get(entry.getKey())), feasible(first), armCounts(entry.getValue())));
        }
        int from = Math.min(offset, all.size());
        int to = Math.min(from + limit, all.size());
        return Optional.of(new CasePage(all.size(), limit, offset, List.copyOf(categories), all.subList(from, to)));
    }

    @Override
    public Optional<CaseDetail> findCase(String runId, String caseId) {
        Optional<JsonNode> summary = readSummary(runId);
        if (summary.isEmpty()) {
            return Optional.empty();
        }
        Map<String, JsonNode> definitions = readCases();
        List<JsonNode> rows = readRows(runId).stream().filter(r -> caseId.equals(r.path("case_id").asText())).toList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        JsonNode definition = definitions.get(caseId);
        List<ArmCase> arms = new ArrayList<>();
        for (var armEntry : iterable(summary.get().path("summary").path("arms").fields())) {
            List<Repeat> repeats = rows.stream().filter(r -> armEntry.getKey().equals(r.path("arm").asText()))
                    .sorted(Comparator.comparingInt(r -> r.path("repeat").asInt()))
                    .map(this::toRepeat).toList();
            if (!repeats.isEmpty()) {
                String label = text(armEntry.getValue(), "name");
                arms.add(new ArmCase(armEntry.getKey(), label == null ? armEntry.getKey() : label, repeats));
            }
        }
        return Optional.of(new CaseDetail(caseId, categoryOf(rows.get(0), definition), userMessage(definition),
                constraints(definition), feasible(rows.get(0)), arms));
    }

    private Repeat toRepeat(JsonNode row) {
        JsonNode score = row.path("score");
        boolean shown = score.path("shown").asBoolean(false);
        Recommendation recommendation = null;
        JsonNode rec = row.path("recommendation");
        if (shown && rec.isObject()) {
            recommendation = new Recommendation(rec.path("meal_id").isNumber() ? rec.path("meal_id").asLong() : null,
                    text(rec, "meal_name"), text(rec, "reason"), text(rec, "no_match_reason"));
        }
        return new Repeat(row.path("repeat").asInt(), text(row, "status"), shown, score.path("unsafe").asBoolean(),
                score.path("correct_outcome").asBoolean(), score.path("hard_violation").asBoolean(),
                score.path("claim_fail").asBoolean(), score.path("hallucinated_dish").asBoolean(),
                score.path("false_no_match").asBoolean(), strings(score.path("issues")), recommendation,
                row.path("llm_calls").asInt(), row.path("cost_usd").asDouble(), null, timeline(row.path("events")));
    }

    private static List<TimelineStep> timeline(JsonNode events) {
        List<TimelineStep> steps = new ArrayList<>();
        if (!events.isArray()) {
            return steps;
        }
        for (JsonNode e : events) {
            String type = e.path("type").asText();
            Integer round = e.path("round").isNumber() ? e.path("round").asInt() : null;
            String label = switch (type) {
                case "run_start", "final" -> null;   // the case itself and the outcome are shown elsewhere
                case "llm_call" -> "model → " + (e.path("tool_calls").isEmpty()
                        ? "final answer" : String.join(", ", strings(e.path("tool_calls"))));
                case "tool_call" -> e.path("tool").asText() + " → " + e.path("summary").asText()
                        + (e.path("is_error").asBoolean() ? " (error)" : "");
                case "forced_final" -> "tools switched off (" + e.path("reason").asText() + ")";
                case "verify" -> e.path("ok").asBoolean() ? "verify → ok"
                        : "verify → FAIL " + String.join(", ", strings(e.path("issues")));
                case "format_retry", "format_failed" -> type + ": " + truncate(e.path("errors").asText(), 120);
                default -> type;
            };
            if (label != null) {
                steps.add(new TimelineStep(type, round, label));
            }
        }
        return steps;
    }

    private static Map<String, ArmCounts> armCounts(List<JsonNode> rows) {
        Map<String, int[]> raw = new LinkedHashMap<>();
        for (JsonNode r : rows) {
            int[] c = raw.computeIfAbsent(r.path("arm").asText(), k -> new int[3]);
            c[0]++;
            c[1] += r.path("score").path("unsafe").asBoolean() ? 1 : 0;
            c[2] += r.path("score").path("correct_outcome").asBoolean() ? 1 : 0;
        }
        Map<String, ArmCounts> out = new LinkedHashMap<>();
        raw.forEach((arm, c) -> out.put(arm, new ArmCounts(c[0], c[1], c[2])));
        return out;
    }

    private static Map<String, List<JsonNode>> groupByCase(List<JsonNode> rows, Map<String, JsonNode> definitions) {
        Map<String, List<JsonNode>> grouped = new LinkedHashMap<>();
        for (JsonNode r : rows) {
            grouped.computeIfAbsent(r.path("case_id").asText(), k -> new ArrayList<>()).add(r);
        }
        // cases.json order when it is available, otherwise by id
        Map<String, List<JsonNode>> ordered = new LinkedHashMap<>();
        definitions.keySet().forEach(id -> {
            if (grouped.containsKey(id)) {
                ordered.put(id, grouped.remove(id));
            }
        });
        new TreeSet<>(grouped.keySet()).forEach(id -> ordered.put(id, grouped.get(id)));
        return ordered;
    }

    private static String categoryOf(JsonNode row, JsonNode definition) {
        String fromRow = text(row, "category");
        return fromRow != null ? fromRow : definition == null ? "unknown" : text(definition, "category");
    }

    private static Boolean feasible(JsonNode row) {
        JsonNode node = row.path("score").path("feasible_exists");
        return node.isBoolean() ? node.asBoolean() : null;
    }

    private static String userMessage(JsonNode definition) {
        return definition == null ? null : text(definition, "user_message");
    }

    private static Constraints constraints(JsonNode definition) {
        if (definition == null) {
            return null;
        }
        return new Constraints(definition.path("max_price").isNumber() ? definition.path("max_price").asDouble() : null,
                strings(definition.path("exclude_allergens")), definition.path("requires_high_protein").asBoolean(false),
                definition.path("min_protein_g").isNumber() ? definition.path("min_protein_g").asDouble() : null,
                text(definition, "meal_time"));
    }

    // ---- files (all path handling is here) ----

    /** The run folder, only if the id is a timestamp and the folder really lies inside the configured root. */
    private Optional<Path> runDir(String runId) {
        if (!supports(runId) || !Files.isDirectory(root)) {
            return Optional.empty();
        }
        Path dir = root.resolve(runId).normalize();
        if (!dir.startsWith(root) || dir.equals(root) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            if (!dir.toRealPath().startsWith(root.toRealPath())) {
                return Optional.empty();
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.of(dir);
    }

    private static Optional<Path> regularFile(Path dir, String name) {
        Path file = dir.resolve(name);
        try {
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= MAX_FILE_BYTES) {
                return Optional.of(file);
            }
        } catch (IOException ignored) {
            // treated as missing
        }
        return Optional.empty();
    }

    /** summary.json of a complete run; empty for an unknown, incomplete or unreadable run. */
    private Optional<JsonNode> readSummary(String runId) {
        Optional<Path> dir = runDir(runId);
        if (dir.isEmpty() || Files.exists(dir.get().resolve("INCOMPLETE.txt"), LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        Optional<Path> file = regularFile(dir.get(), "summary.json");
        if (file.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode node = mapper.readTree(Files.readString(file.get(), StandardCharsets.UTF_8));
            return node.path("summary").path("arms").isObject() ? Optional.of(node) : Optional.empty();
        } catch (IOException e) {
            log.warn("evaluation run {} has an unreadable summary.json ({})", runId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private List<JsonNode> readRows(String runId) {
        Optional<Path> dir = runDir(runId);
        Optional<Path> file = dir.flatMap(d -> regularFile(d, "runs.jsonl"));
        if (file.isEmpty()) {
            return List.of();
        }
        try {
            long modified = Files.getLastModifiedTime(file.get()).toMillis();
            long size = Files.size(file.get());
            CachedRows cached = rowsCache.get(runId);
            if (cached != null && cached.modified == modified && cached.size == size) {
                return cached.rows;
            }
            List<JsonNode> rows = new ArrayList<>();
            for (String line : Files.readAllLines(file.get(), StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    rows.add(mapper.readTree(line));
                }
            }
            rowsCache.put(runId, new CachedRows(modified, size, List.copyOf(rows)));
            return rows;
        } catch (IOException e) {
            log.warn("evaluation run {} has an unreadable runs.jsonl ({})", runId, e.getClass().getSimpleName());
            return List.of();
        }
    }

    private Map<String, JsonNode> readCases() {
        try {
            if (!Files.isRegularFile(casesFile) || Files.size(casesFile) > MAX_FILE_BYTES) {
                return Map.of();
            }
            long modified = Files.getLastModifiedTime(casesFile).toMillis();
            long size = Files.size(casesFile);
            CachedCases cached = casesCache;
            if (cached != null && cached.modified == modified && cached.size == size) {
                return cached.cases;
            }
            Map<String, JsonNode> cases = new LinkedHashMap<>();
            for (JsonNode c : mapper.readTree(Files.readString(casesFile, StandardCharsets.UTF_8))) {
                cases.put(c.path("id").asText(), c);
            }
            casesCache = new CachedCases(modified, size, cases);
            return cases;
        } catch (IOException e) {
            log.warn("the evaluation cases file is unreadable ({})", e.getClass().getSimpleName());
            return Map.of();
        }
    }

    // ---- small helpers ----

    private static String createdAt(String runId) {
        try {
            return LocalDateTime.parse(runId, STAMP).toInstant(ZoneOffset.UTC).toString();   // run_eval.py names runs by UTC time
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static Integer integer(JsonNode node, String field) {
        return node.path(field).isInt() ? node.path(field).asInt() : null;
    }

    private static List<String> strings(JsonNode array) {
        List<String> list = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(n -> list.add(n.asText()));
        }
        return list;
    }

    private static void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static <T> Iterable<T> iterable(java.util.Iterator<T> iterator) {
        return () -> iterator;
    }
}
