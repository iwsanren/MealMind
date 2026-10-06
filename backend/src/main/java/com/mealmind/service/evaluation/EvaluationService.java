package com.mealmind.service.evaluation;

import com.mealmind.dto.evaluation.EvaluationCases.CaseDetail;
import com.mealmind.dto.evaluation.EvaluationCases.CasePage;
import com.mealmind.dto.evaluation.EvaluationRun;
import com.mealmind.dto.evaluation.EvaluationRunSummary;
import com.mealmind.exception.MealException;
import com.mealmind.exception.NotFoundException;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The one entry point for evaluation data. It checks every id that comes from the outside (a short, plain character set;
 * each source then applies its own stricter format) and hands the request to the source that owns the run id.
 */
@Service
public class EvaluationService {

    static final int MAX_LIMIT = 100;
    private static final Pattern RUN_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern CASE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");
    private static final Pattern CATEGORY = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");

    private final List<EvaluationRunSource> sources;

    public EvaluationService(List<EvaluationRunSource> sources) {
        this.sources = sources;
    }

    public List<EvaluationRunSummary> listRuns() {
        return sources.stream().flatMap(s -> s.listRuns().stream())
                .sorted(Comparator.comparing(EvaluationRunSummary::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public EvaluationRun getRun(String runId) {
        return source(runId).findRun(runId).orElseThrow(() -> new NotFoundException("Evaluation run not found"));
    }

    public CasePage listCases(String runId, String category, int limit, int offset) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new MealException("limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new MealException("offset must not be negative");
        }
        if (category != null && !CATEGORY.matcher(category).matches()) {
            throw new MealException("category is not valid");
        }
        return source(runId).listCases(runId, category, limit, offset)
                .orElseThrow(() -> new NotFoundException("Evaluation run not found"));
    }

    public CaseDetail getCase(String runId, String caseId) {
        if (caseId == null || !CASE_ID.matcher(caseId).matches()) {
            throw new MealException("caseId is not valid");
        }
        return source(runId).findCase(runId, caseId).orElseThrow(() -> new NotFoundException("Case not found in this run"));
    }

    /** Unknown or malformed ids and ids no source owns look the same to the caller: 400 for a bad shape, else 404. */
    private EvaluationRunSource source(String runId) {
        if (runId == null || !RUN_ID.matcher(runId).matches()) {
            throw new MealException("runId is not valid");
        }
        return sources.stream().filter(s -> s.supports(runId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Evaluation run not found"));
    }
}
