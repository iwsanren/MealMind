package com.mealmind.service.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mealmind.config.EvaluationProperties;
import com.mealmind.dto.evaluation.EvaluationCases.CaseDetail;
import com.mealmind.dto.evaluation.EvaluationCases.CasePage;
import com.mealmind.dto.evaluation.EvaluationRun;
import com.mealmind.dto.evaluation.EvaluationRunSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Reads small fixture folders written by the test; one extra test reads the real run in the repository when present. */
class FileEvaluationRunSourceTest {

    private static final String RUN = "20261005-171415";

    @TempDir
    Path temp;

    Path results;
    Path casesFile;

    @BeforeEach
    void setUp() throws IOException {
        results = Files.createDirectories(temp.resolve("results"));
        casesFile = temp.resolve("cases.json");
        Files.writeString(casesFile, """
                [{"id":"a1","category":"allergy","user_message":"I'm allergic to shellfish.","max_price":null,
                  "exclude_allergens":["shellfish"],"requires_high_protein":false,"min_protein_g":null,"meal_time":"Dinner"},
                 {"id":"b1","category":"budget","user_message":"Cheap lunch under 6 dollars.","max_price":6,
                  "exclude_allergens":[],"requires_high_protein":false,"min_protein_g":null,"meal_time":"Lunch"}]""");
    }

    private FileEvaluationRunSource source() {
        return new FileEvaluationRunSource(new EvaluationProperties(results.toString(), casesFile.toString()), new ObjectMapper());
    }

    private static String rate(int k, int n) {
        return String.format("{\"k\":%d,\"n\":%d,\"rate\":%s,\"ci95\":[0.1,0.5]}", k, n, (double) k / n);
    }

    private static String arm(String name, int unsafe) {
        return "{\"name\":\"" + name + "\",\"runs\":4,\"unsafe\":" + rate(unsafe, 4) + ",\"correct_outcome\":" + rate(4 - unsafe, 4)
                + ",\"hallucinated_dish\":" + rate(0, 4) + ",\"avg_llm_calls\":1.5,\"avg_cost_usd\":0.002,\"latency_ms_p50\":999}";
    }

    private void writeRun(String id, String data) throws IOException {
        Path dir = Files.createDirectories(results.resolve(id));
        Files.writeString(dir.resolve("summary.json"), """
                {"meta":{"stamp":"%s","model":"test-model","temperature":0.0,"agent_prompt":"v5","baseline_prompt":"v1",
                         "n_cases":2,"repeats":2,"spent_usd":0.12,"data":"%s"},
                 "summary":{"arms":{"A":%s,"C":%s},
                   "paired":{"A-C:unsafe":{"cases":2,"mean_diff":0.5,"bootstrap_ci95":[0.1,0.9],"left_higher":1,"right_higher":0,"equal":1}},
                   "by_category":{"A":{"allergy":{"runs":2,"unsafe":2,"correct_outcome":0},"budget":{"runs":2,"unsafe":0,"correct_outcome":2}},
                                  "C":{"allergy":{"runs":2,"unsafe":0,"correct_outcome":2},"budget":{"runs":2,"unsafe":0,"correct_outcome":2}}}},
                 "guard":{"blocked_case_ids":["m1"],"false_positives":["t1"],"false_negatives":[]}}"""
                .formatted(id, data, arm("baseline", 2), arm("agent", 0)));
        Files.writeString(dir.resolve("runs.jsonl"), String.join("\n",
                row("A", "a1", 0, true, "allergy", true), row("A", "a1", 1, true, "allergy", true),
                row("A", "b1", 0, false, "budget", true), row("A", "b1", 1, false, "budget", true),
                row("C", "a1", 0, false, "allergy", true), row("C", "a1", 1, false, "allergy", false),
                row("C", "b1", 0, false, "budget", true), row("C", "b1", 1, false, "budget", true)) + "\n");
    }

    private static String row(String arm, String caseId, int repeat, boolean unsafe, String category, boolean shown) {
        String events = "C".equals(arm)
                ? "[{\"type\":\"run_start\"},{\"type\":\"llm_call\",\"round\":1,\"tool_calls\":[\"search_meals\"]},"
                  + "{\"type\":\"tool_call\",\"round\":1,\"tool\":\"search_meals\",\"summary\":\"3 meals\",\"is_error\":false},"
                  + "{\"type\":\"verify\",\"round\":2,\"ok\":false,\"issues\":[\"CONTAINS_ALLERGEN\"]},{\"type\":\"final\"}]"
                : "null";
        return "{\"arm\":\"" + arm + "\",\"case_id\":\"" + caseId + "\",\"repeat\":" + repeat + ",\"status\":\""
                + (shown ? "SUCCESS" : "UNVERIFIED") + "\",\"category\":\"" + category + "\",\"llm_calls\":2,\"cost_usd\":0.001,"
                + "\"recommendation\":{\"meal_id\":7,\"meal_name\":\"Bowl\",\"reason\":\"Because.\",\"claims\":[],\"no_match_reason\":null},"
                + "\"events\":" + events + ",\"score\":{\"shown\":" + shown + ",\"unsafe\":" + unsafe + ",\"correct_outcome\":" + !unsafe
                + ",\"hard_violation\":" + unsafe + ",\"claim_fail\":false,\"hallucinated_dish\":false,\"false_no_match\":false,"
                + "\"feasible_exists\":true,\"issues\":" + (unsafe ? "[\"CONTAINS_ALLERGEN\"]" : "[]") + "}}";
    }

    // ---- listing ----

    @Test
    void listsCompleteRunsNewestFirstAndSkipsEverythingElse() throws IOException {
        writeRun("20261001-100000", "synthetic (x)");
        writeRun(RUN, "synthetic (x)");
        writeRun("20261002-100000", "synthetic (x)");
        Files.writeString(results.resolve("20261002-100000").resolve("INCOMPLETE.txt"), "API failures");   // invalid run
        Files.createDirectories(results.resolve("not-a-run"));                                              // wrong name
        Files.createDirectories(results.resolve("20261003-100000"));                                         // no summary.json
        Files.writeString(results.resolve("full_run.log"), "log");                                           // a file, not a run

        List<EvaluationRunSummary> runs = source().listRuns();

        assertThat(runs).extracting(EvaluationRunSummary::runId).containsExactly(RUN, "20261001-100000");
        EvaluationRunSummary first = runs.get(0);
        assertThat(first.source()).isEqualTo("offline_golden_set");
        assertThat(first.dataKind()).isEqualTo("synthetic");
        assertThat(first.createdAt()).isEqualTo("2026-10-05T17:14:15Z");
        assertThat(first.title()).isEqualTo("test-model · 2 cases × 2 repeats");
    }

    @Test
    void aMissingResultsFolderIsJustNoRuns() throws IOException {
        Files.delete(casesFile);
        FileEvaluationRunSource missing = new FileEvaluationRunSource(
                new EvaluationProperties(temp.resolve("does-not-exist").toString(), casesFile.toString()), new ObjectMapper());
        assertThat(missing.listRuns()).isEmpty();
        assertThat(missing.findRun(RUN)).isEmpty();
    }

    // ---- one run ----

    @Test
    void mapsTheRunIntoTheSourceIndependentShape() throws IOException {
        writeRun(RUN, "synthetic (evaluation/build_dataset.py)");

        EvaluationRun run = source().findRun(RUN).orElseThrow();

        assertThat(run.schemaVersion()).isEqualTo(1);
        assertThat(run.dataKind()).isEqualTo("synthetic");
        assertThat(run.dataNote()).contains("synthetic").contains("not how accurate");
        assertThat(run.meta().model()).isEqualTo("test-model");
        assertThat(run.meta().promptVersions()).containsEntry("agent", "v5").containsEntry("baseline", "v1");
        assertThat(run.arms()).extracting(EvaluationRun.ArmResult::id).containsExactly("A", "C");
        var unsafeA = run.arms().get(0).metrics().get("unsafe");
        assertThat(unsafeA.k()).isEqualTo(2);
        assertThat(unsafeA.n()).isEqualTo(4);
        assertThat(unsafeA.value()).isEqualTo(0.5);
        assertThat(unsafeA.ciLow()).isEqualTo(0.1);
        assertThat(run.arms().get(0).metrics().get("avg_cost_usd").value()).isEqualTo(0.002);
        assertThat(run.arms().get(0).metrics().get("avg_cost_usd").k()).isNull();
        // latency is never exposed (it includes rate-limit waiting), and only metrics that exist are defined
        assertThat(run.arms().get(0).metrics()).doesNotContainKey("latency_ms_p50");
        assertThat(run.metrics()).extracting(EvaluationRun.MetricDefinition::key).contains("unsafe", "correct_outcome", "avg_llm_calls")
                .doesNotContain("false_no_match");
        assertThat(run.metrics().stream().filter(EvaluationRun.MetricDefinition::headline)).extracting(EvaluationRun.MetricDefinition::key)
                .containsExactly("unsafe", "correct_outcome");
        assertThat(run.breakdown().groups()).extracting(EvaluationRun.BreakdownGroup::key).containsExactly("allergy", "budget");
        assertThat(run.breakdown().groups().get(0).arms().get("A")).containsEntry("runs", 2).containsEntry("unsafe", 2);
        assertThat(run.comparisons()).singleElement().satisfies(c -> {
            assertThat(c.left()).isEqualTo("A");
            assertThat(c.right()).isEqualTo("C");
            assertThat(c.metric()).isEqualTo("unsafe");
            assertThat(c.leftHigher()).isEqualTo(1);
        });
        assertThat(run.gate().blockedCaseIds()).containsExactly("m1");
        assertThat(run.gate().falsePositives()).containsExactly("t1");
        assertThat(run.caveats()).hasSize(3);
    }

    @Test
    void dataThatDoesNotSayItIsSyntheticIsNotTreatedAsReal() throws IOException {
        writeRun(RUN, "collected from somewhere");
        EvaluationRun run = source().findRun(RUN).orElseThrow();
        assertThat(run.dataKind()).isEqualTo("unknown");
        assertThat(run.dataNote()).contains("do not read these numbers as real-world accuracy");
    }

    @Test
    void anIncompleteRunDoesNotExist() throws IOException {
        writeRun(RUN, "synthetic (x)");
        Files.writeString(results.resolve(RUN).resolve("INCOMPLETE.txt"), "do not quote");
        assertThat(source().findRun(RUN)).isEmpty();
        assertThat(source().listCases(RUN, null, 10, 0)).isEmpty();
        assertThat(source().findCase(RUN, "a1")).isEmpty();
    }

    @Test
    void aBrokenSummaryIsNotAvailableInsteadOfCrashing() throws IOException {
        Files.createDirectories(results.resolve(RUN));
        Files.writeString(results.resolve(RUN).resolve("summary.json"), "{ this is not json");
        assertThat(source().findRun(RUN)).isEmpty();
        assertThat(source().listRuns()).isEmpty();
    }

    // ---- path safety ----

    @Test
    void idsThatAreNotATimestampNeverTouchTheFileSystem() throws IOException {
        Path secret = Files.createDirectories(temp.resolve("secret"));
        Files.writeString(secret.resolve("summary.json"), "{\"summary\":{\"arms\":{\"A\":{}}}}");
        FileEvaluationRunSource source = source();

        for (String id : List.of("../secret", "..", ".", "/etc/passwd", "C:\\Windows", RUN + "/../../secret", RUN + "/..",
                "20261005-17141", "2026100517141500", "20261005_171415", "", " 20261005-171415", "..\\secret", "%2e%2e%2fsecret")) {
            assertThat(source.supports(id)).as(id).isFalse();
            assertThat(source.findRun(id)).as(id).isEmpty();
            assertThat(source.listCases(id, null, 10, 0)).as(id).isEmpty();
            assertThat(source.findCase(id, "a1")).as(id).isEmpty();
        }
        assertThat(source.supports(null)).isFalse();
    }

    @Test
    void aRunFolderThatIsALinkToSomewhereElseIsRefused() throws IOException {
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("summary.json"), "{\"meta\":{},\"summary\":{\"arms\":{\"A\":{\"name\":\"x\",\"runs\":1}}}}");
        try {
            Files.createSymbolicLink(results.resolve(RUN), outside);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "symbolic links are not available here");
        }
        assertThat(source().findRun(RUN)).isEmpty();
        assertThat(source().listRuns()).isEmpty();
    }

    @Test
    void aSummaryFileThatIsALinkToSomewhereElseIsRefused() throws IOException {
        Path dir = Files.createDirectories(results.resolve(RUN));
        Path outside = temp.resolve("outside.json");
        Files.writeString(outside, "{\"meta\":{},\"summary\":{\"arms\":{\"A\":{\"name\":\"x\",\"runs\":1}}}}");
        try {
            Files.createSymbolicLink(dir.resolve("summary.json"), outside);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "symbolic links are not available here");
        }
        assertThat(source().findRun(RUN)).isEmpty();
    }

    // ---- cases ----

    @Test
    void listsCasesWithTheirConstraintsAndPerArmCounts() throws IOException {
        writeRun(RUN, "synthetic (x)");

        CasePage page = source().listCases(RUN, null, 50, 0).orElseThrow();

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.categories()).containsExactly("allergy", "budget");
        assertThat(page.items()).extracting(c -> c.caseId()).containsExactly("a1", "b1");   // cases.json order
        var allergy = page.items().get(0);
        assertThat(allergy.userMessage()).isEqualTo("I'm allergic to shellfish.");
        assertThat(allergy.constraints().excludeAllergens()).containsExactly("shellfish");
        assertThat(allergy.constraints().maxPrice()).isNull();
        assertThat(allergy.constraints().mealTime()).isEqualTo("Dinner");
        assertThat(allergy.arms().get("A").unsafe()).isEqualTo(2);
        assertThat(allergy.arms().get("C").correctOutcome()).isEqualTo(2);
        assertThat(allergy.feasibleExists()).isTrue();
    }

    @Test
    void filtersByCategoryAndPages() throws IOException {
        writeRun(RUN, "synthetic (x)");
        assertThat(source().listCases(RUN, "budget", 50, 0).orElseThrow().items()).extracting(c -> c.caseId()).containsExactly("b1");
        CasePage second = source().listCases(RUN, null, 1, 1).orElseThrow();
        assertThat(second.total()).isEqualTo(2);
        assertThat(second.items()).extracting(c -> c.caseId()).containsExactly("b1");
        assertThat(source().listCases(RUN, null, 10, 99).orElseThrow().items()).isEmpty();
    }

    @Test
    void worksWithoutTheCasesFileJustWithoutTheMessage() throws IOException {
        writeRun(RUN, "synthetic (x)");
        Files.delete(casesFile);
        CasePage page = source().listCases(RUN, null, 50, 0).orElseThrow();
        assertThat(page.items()).extracting(c -> c.caseId()).containsExactly("a1", "b1");
        assertThat(page.items().get(0).userMessage()).isNull();
        assertThat(page.items().get(0).category()).isEqualTo("allergy");
    }

    @Test
    void theCaseDetailShowsEveryRepeatAndHidesWithheldAnswers() throws IOException {
        writeRun(RUN, "synthetic (x)");

        CaseDetail detail = source().findCase(RUN, "a1").orElseThrow();

        assertThat(detail.arms()).extracting(a -> a.id()).containsExactly("A", "C");
        var armA = detail.arms().get(0);
        assertThat(armA.label()).isEqualTo("baseline");
        assertThat(armA.repeats()).hasSize(2);
        var unsafeRepeat = armA.repeats().get(0);
        assertThat(unsafeRepeat.unsafe()).isTrue();
        assertThat(unsafeRepeat.issues()).containsExactly("CONTAINS_ALLERGEN");
        assertThat(unsafeRepeat.recommendation().mealName()).isEqualTo("Bowl");
        assertThat(unsafeRepeat.traceId()).isNull();
        assertThat(unsafeRepeat.timeline()).isEmpty();                                    // arm without tools

        var agentRepeats = detail.arms().get(1).repeats();
        assertThat(agentRepeats.get(0).timeline()).extracting(t -> t.label())
                .containsExactly("model → search_meals", "search_meals → 3 meals", "verify → FAIL CONTAINS_ALLERGEN");
        var withheld = agentRepeats.get(1);
        assertThat(withheld.shown()).isFalse();
        assertThat(withheld.status()).isEqualTo("UNVERIFIED");
        assertThat(withheld.recommendation()).isNull();                                   // a withheld draft is never shown as an answer
    }

    @Test
    void anUnknownCaseOrRunIsEmpty() throws IOException {
        writeRun(RUN, "synthetic (x)");
        assertThat(source().findCase(RUN, "zz")).isEqualTo(Optional.empty());
        assertThat(source().findCase("20200101-000000", "a1")).isEmpty();
    }

    // ---- the real run in the repository ----

    @Test
    void theRealRunReproducesTheHeadlineNumbers() {
        Path realResults = Path.of("..", "evaluation", "results");
        assumeTrue(Files.isDirectory(realResults.resolve(RUN)), "the real run is not in this checkout");
        FileEvaluationRunSource real = new FileEvaluationRunSource(
                new EvaluationProperties(realResults.toString(), Path.of("..", "evaluation", "cases.json").toString()), new ObjectMapper());

        EvaluationRun run = real.findRun(RUN).orElseThrow();

        assertThat(run.dataKind()).isEqualTo("synthetic");
        assertThat(run.arms()).hasSize(3);
        assertThat(run.arms().get(0).metrics().get("unsafe").k()).isEqualTo(31);
        assertThat(run.arms().get(0).metrics().get("unsafe").n()).isEqualTo(102);
        assertThat(run.arms().get(2).metrics().get("unsafe").k()).isEqualTo(3);
        assertThat(run.arms().get(2).metrics().get("correct_outcome").k()).isEqualTo(93);
        assertThat(run.arms().get(1).metrics().get("false_no_match").k()).isEqualTo(25);
        assertThat(real.listCases(RUN, null, 100, 0).orElseThrow().total()).isEqualTo(34);
        assertThat(real.findCase(RUN, "n1").orElseThrow().arms()).hasSize(3);
    }
}
