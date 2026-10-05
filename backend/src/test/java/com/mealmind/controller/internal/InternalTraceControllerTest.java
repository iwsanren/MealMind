package com.mealmind.controller.internal;

import com.mealmind.entity.RequestTraceRow;
import com.mealmind.service.trace.AgentTraceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of POST /internal/v1/traces. AgentTraceService is mocked: this does not test SQL. */
@WebMvcTest(InternalTraceController.class)
class InternalTraceControllerTest {

    private static final String VALID = """
            {"traceId":"t-1","sessionId":"sess_1","userId":7,"status":"SUCCESS","durationMs":1234,
             "events":[{"round":1,"tool":"search_meals"},{"round":2,"final":true}]}""";

    @Autowired
    MockMvc mockMvc;

    @MockBean
    AgentTraceService agentTraceService;

    private ResultActions write(String json) throws Exception {
        return mockMvc.perform(post("/internal/v1/traces").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void mapsTheRequestOntoTheTraceRow() throws Exception {
        write(VALID).andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("t-1"))
                .andExpect(jsonPath("$.eventCount").value(2));

        ArgumentCaptor<RequestTraceRow> row = ArgumentCaptor.forClass(RequestTraceRow.class);
        verify(agentTraceService).insert(row.capture());
        assertThat(row.getValue().getTraceId()).isEqualTo("t-1");
        assertThat(row.getValue().getSessionId()).isEqualTo("sess_1");
        assertThat(row.getValue().getUserId()).isEqualTo(7L);
        assertThat(row.getValue().getStatus()).isEqualTo("SUCCESS");
        assertThat(row.getValue().getDurationMs()).isEqualTo(1234L);
        assertThat(row.getValue().getEventCount()).isEqualTo(2);
        assertThat(row.getValue().getTraceJson()).startsWith("[").contains("search_meals");
    }

    @Test
    void missingRequiredFieldsAreA400AndNothingIsWritten() throws Exception {
        write(VALID.replace("\"traceId\":\"t-1\",", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("traceId")));
        write(VALID.replace("\"sessionId\":\"sess_1\",", "")).andExpect(status().isBadRequest());
        write(VALID.replace("\"userId\":7,", "")).andExpect(status().isBadRequest());
        write(VALID.replace("\"status\":\"SUCCESS\",", "")).andExpect(status().isBadRequest());
        verify(agentTraceService, never()).insert(any());
    }

    @Test
    void eventsMustBeAJsonArray() throws Exception {
        write(VALID.replaceAll("\"events\":\\[.*\\]", "\"events\":{\"round\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("JSON array")));
        write(VALID.replaceAll(",\\s*\"events\":\\[.*\\]", "")).andExpect(status().isBadRequest());
    }

    @Test
    void oversizeIdsAndNegativeDurationAreA400() throws Exception {
        write(VALID.replace("t-1", "x".repeat(129))).andExpect(status().isBadRequest());
        write(VALID.replace("sess_1", "x".repeat(65))).andExpect(status().isBadRequest());
        write(VALID.replace("1234", "-1")).andExpect(status().isBadRequest());
    }

    @Test
    void duplicateTraceIdBecomesA400() throws Exception {
        when(agentTraceService.insert(any())).thenThrow(new DuplicateKeyException("uk_request_trace"));

        write(VALID).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("already exists")));
    }

    /**
     * Pins an existing quirk instead of hiding it: GlobalExceptionHandler's catch-all turns a body
     * that is not valid JSON into a 500. Callers (ai-service) must treat both 4xx and 5xx as errors.
     */
    @Test
    void malformedJsonBodyIsCurrentlyA500() throws Exception {
        write("{not json").andExpect(status().isInternalServerError());
    }
}
