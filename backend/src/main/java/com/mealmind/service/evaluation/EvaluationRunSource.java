package com.mealmind.service.evaluation;

import com.mealmind.dto.evaluation.EvaluationCases;
import com.mealmind.dto.evaluation.EvaluationRun;
import com.mealmind.dto.evaluation.EvaluationRunSummary;

import java.util.List;
import java.util.Optional;

/**
 * Where evaluation runs come from. Today that is the output files of the offline evaluation (FileEvaluationRunSource);
 * a source that scores stored traces can be added next to it later without touching the controller or the page.
 * Every source owns its own run ids; {@link #supports(String)} says which ids are its.
 */
public interface EvaluationRunSource {

    boolean supports(String runId);

    /** Complete runs only, newest first. A source that has nothing returns an empty list. */
    List<EvaluationRunSummary> listRuns();

    Optional<EvaluationRun> findRun(String runId);

    /** Empty when the run does not exist; category may be null (all categories). */
    Optional<EvaluationCases.CasePage> listCases(String runId, String category, int limit, int offset);

    Optional<EvaluationCases.CaseDetail> findCase(String runId, String caseId);
}
