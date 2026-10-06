package com.mealmind.controller.evaluation;

import com.mealmind.dto.evaluation.EvaluationCases.CasePage;
import com.mealmind.dto.evaluation.EvaluationRun;
import com.mealmind.dto.evaluation.EvaluationRunSummary;
import com.mealmind.exception.MealException;
import com.mealmind.exception.NotFoundException;
import com.mealmind.service.evaluation.EvaluationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of /api/v1/evaluations. The service is mocked; id and path safety are tested where they are enforced. */
@WebMvcTest(EvaluationController.class)
class EvaluationControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    EvaluationService service;

    @Test
    void listsRuns() throws Exception {
        when(service.listRuns()).thenReturn(List.of(new EvaluationRunSummary("20261005-171415", "offline_golden_set",
                "synthetic", "m · 34 cases × 3 repeats", "2026-10-05T17:14:15Z", "m", 34, 3)));

        mockMvc.perform(get("/api/v1/evaluations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runId").value("20261005-171415"))
                .andExpect(jsonPath("$[0].dataKind").value("synthetic"));
    }

    @Test
    void returnsOneRun() throws Exception {
        when(service.getRun("20261005-171415")).thenReturn(new EvaluationRun(1, "20261005-171415", "offline_golden_set",
                "synthetic", "note", null, List.of(), List.of(new EvaluationRun.ArmResult("A", "baseline", 102,
                Map.of("unsafe", new EvaluationRun.MetricValue(31, 102, 0.30, 0.22, 0.40)))), null, List.of(), null, List.of("c")));

        mockMvc.perform(get("/api/v1/evaluations/20261005-171415"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.arms[0].metrics.unsafe.k").value(31))
                .andExpect(jsonPath("$.arms[0].metrics.unsafe.ciLow").value(0.22));
    }

    @Test
    void anUnknownRunIs404() throws Exception {
        when(service.getRun("20200101-000000")).thenThrow(new NotFoundException("Evaluation run not found"));

        mockMvc.perform(get("/api/v1/evaluations/20200101-000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Evaluation run not found"));
    }

    @Test
    void aBadIdIs400() throws Exception {
        when(service.getRun(any())).thenThrow(new MealException("runId is not valid"));

        mockMvc.perform(get("/api/v1/evaluations/bad..id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("runId is not valid"));
    }

    @Test
    void casesUseDefaultsAndPassTheFilters() throws Exception {
        when(service.listCases(any(), any(), anyInt(), anyInt())).thenReturn(new CasePage(0, 50, 0, List.of(), List.of()));

        mockMvc.perform(get("/api/v1/evaluations/20261005-171415/cases")).andExpect(status().isOk());
        verify(service).listCases("20261005-171415", null, 50, 0);

        mockMvc.perform(get("/api/v1/evaluations/20261005-171415/cases?category=budget&limit=5&offset=10")).andExpect(status().isOk());
        verify(service).listCases(eq("20261005-171415"), eq("budget"), eq(5), eq(10));
    }

    @Test
    void anUrlNobodyServesIs404NotA500() throws Exception {
        mockMvc.perform(get("/api/v1/evaluations/20261005-171415/../backend"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/nothing-here"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Not found"));
    }

    @Test
    void aNonNumericLimitIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/evaluations/20261005-171415/cases?limit=abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'limit'"));
    }
}
