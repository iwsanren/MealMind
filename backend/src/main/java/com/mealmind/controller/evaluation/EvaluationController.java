package com.mealmind.controller.evaluation;

import com.mealmind.dto.evaluation.EvaluationCases.CaseDetail;
import com.mealmind.dto.evaluation.EvaluationCases.CasePage;
import com.mealmind.dto.evaluation.EvaluationRun;
import com.mealmind.dto.evaluation.EvaluationRunSummary;
import com.mealmind.service.evaluation.EvaluationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only access to evaluation runs for the Evaluations page. Nothing here starts an evaluation: running one costs
 * real money, so it stays a command-line action.
 */
@RestController
@RequestMapping("/api/v1/evaluations")
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @GetMapping
    public List<EvaluationRunSummary> list() {
        return evaluationService.listRuns();
    }

    @GetMapping("/{runId}")
    public EvaluationRun get(@PathVariable String runId) {
        return evaluationService.getRun(runId);
    }

    @GetMapping("/{runId}/cases")
    public CasePage cases(@PathVariable String runId,
                          @RequestParam(required = false) String category,
                          @RequestParam(defaultValue = "50") int limit,
                          @RequestParam(defaultValue = "0") int offset) {
        return evaluationService.listCases(runId, category, limit, offset);
    }

    @GetMapping("/{runId}/cases/{caseId}")
    public CaseDetail caseDetail(@PathVariable String runId, @PathVariable String caseId) {
        return evaluationService.getCase(runId, caseId);
    }
}
