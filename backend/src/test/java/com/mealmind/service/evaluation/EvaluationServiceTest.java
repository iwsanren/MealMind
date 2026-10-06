package com.mealmind.service.evaluation;

import com.mealmind.dto.evaluation.EvaluationCases.CasePage;
import com.mealmind.dto.evaluation.EvaluationRun;
import com.mealmind.dto.evaluation.EvaluationRunSummary;
import com.mealmind.exception.MealException;
import com.mealmind.exception.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Id checks and routing between sources; a second fake source stands in for "scored real traces" later. */
class EvaluationServiceTest {

    private static EvaluationRunSummary summary(String id, String createdAt) {
        return new EvaluationRunSummary(id, "fake", "synthetic", "t", createdAt, "m", 1, 1);
    }

    private final EvaluationRunSource files = mock(EvaluationRunSource.class);
    private final EvaluationRunSource traces = mock(EvaluationRunSource.class);
    private final EvaluationService service = new EvaluationService(List.of(files, traces));

    @Test
    void mergesSourcesNewestFirstAndRoutesAnIdToTheSourceThatOwnsIt() {
        when(files.listRuns()).thenReturn(List.of(summary("20261001-000000", "2026-10-01T00:00:00Z")));
        when(traces.listRuns()).thenReturn(List.of(summary("trace-run-1", "2026-10-03T00:00:00Z"), summary("trace-run-0", null)));
        assertThat(service.listRuns()).extracting(EvaluationRunSummary::runId)
                .containsExactly("trace-run-1", "20261001-000000", "trace-run-0");           // no date sorts last

        when(traces.supports("trace-run-1")).thenReturn(true);
        when(traces.findRun("trace-run-1")).thenReturn(Optional.of(mock(EvaluationRun.class)));
        assertThat(service.getRun("trace-run-1")).isNotNull();
        verify(files, never()).findRun(any());
    }

    @Test
    void anIdNoSourceOwnsIsNotFound() {
        assertThatThrownBy(() -> service.getRun("20200101-000000")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.listCases("20200101-000000", null, 10, 0)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getCase("20200101-000000", "a1")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aRunTheSourceDoesNotHaveIsNotFound() {
        when(files.supports("20261005-171415")).thenReturn(true);
        when(files.findRun("20261005-171415")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getRun("20261005-171415")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void malformedIdsAreRejectedBeforeAnySourceIsAsked() {
        for (String id : new String[]{"../x", "a/b", "a b", "a.b", "", "x".repeat(65), "%2e%2e", "a;b", "a\\b"}) {
            assertThatThrownBy(() -> service.getRun(id)).as(id).isInstanceOf(MealException.class);
            assertThatThrownBy(() -> service.listCases(id, null, 10, 0)).as(id).isInstanceOf(MealException.class);
            assertThatThrownBy(() -> service.getCase(id, "a1")).as(id).isInstanceOf(MealException.class);
        }
        assertThatThrownBy(() -> service.getRun(null)).isInstanceOf(MealException.class);
        verify(files, never()).findRun(any());
        verify(traces, never()).findRun(any());
    }

    @Test
    void caseAndCategoryAndPagingAreChecked() {
        when(files.supports("20261005-171415")).thenReturn(true);
        when(files.listCases(any(), any(), anyInt(), anyInt())).thenReturn(Optional.of(new CasePage(0, 10, 0, List.of(), List.of())));

        for (String caseId : new String[]{"../a", "a b", "", "x".repeat(33)}) {
            assertThatThrownBy(() -> service.getCase("20261005-171415", caseId)).as(caseId).isInstanceOf(MealException.class);
        }
        assertThatThrownBy(() -> service.listCases("20261005-171415", "a b", 10, 0)).isInstanceOf(MealException.class);
        assertThatThrownBy(() -> service.listCases("20261005-171415", null, 0, 0)).isInstanceOf(MealException.class);
        assertThatThrownBy(() -> service.listCases("20261005-171415", null, 101, 0)).isInstanceOf(MealException.class);
        assertThatThrownBy(() -> service.listCases("20261005-171415", null, 10, -1)).isInstanceOf(MealException.class);
        assertThat(service.listCases("20261005-171415", "budget", 100, 0)).isNotNull();
    }
}
