package com.mealmind.controller.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mealmind.dto.trace.TraceLabelRequest;
import com.mealmind.entity.RequestTraceRow;
import com.mealmind.model.SlotBundle;
import com.mealmind.service.trace.AgentTraceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack test: controller -> service -> mapper -> real MySQL (Flyway
 * migrations under db/migration run at context startup, same as the running
 * app). Requires a reachable database matching application.yml's datasource - on this
 * machine that means SPRING_DATASOURCE_URL pointed at 127.0.0.1 (see the
 * project's other "run the app locally" notes; "localhost" resolves to an
 * IPv6 address here that MySQL isn't listening on).
 *
 * @Transactional rolls back every insert/update this test makes at the end
 * of each test method, so reruns never accumulate leftover trace rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class AgentTraceControllerIntegrationTest {

    private static final String BASE = "/api/v1/debug";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentTraceService agentTraceService;

    @Autowired
    private ObjectMapper objectMapper;

    private RequestTraceRow seedTrace(String sessionId) {
        RequestTraceRow row = new RequestTraceRow();
        row.setTraceId("trace_it_" + UUID.randomUUID().toString().replace("-", ""));
        row.setSessionId(sessionId);
        row.setUserId(1L);
        row.setStatus("SUCCESS");
        row.setEventCount(3);
        row.setDurationMs(120L);
        row.setTraceJson("{\"events\":[]}");
        agentTraceService.insert(row);
        return row;
    }

    @Test
    void fullLifecycle_insertQueryAndLabel() throws Exception {
        RequestTraceRow row = seedTrace("sess_it_lifecycle");

        // findByTraceId
        mockMvc.perform(get(BASE + "/traces/{traceId}", row.getTraceId())
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sess_it_lifecycle"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.expectedIntent").doesNotExist());

        // findBySessionId
        mockMvc.perform(get(BASE + "/sessions/{sessionId}/traces", "sess_it_lifecycle")
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].traceId").value(row.getTraceId()));

        // findByTimeRange
        String startAt = LocalDateTime.now().minusMinutes(5).format(ISO);
        String endAt = LocalDateTime.now().plusMinutes(5).format(ISO);
        mockMvc.perform(get(BASE + "/traces")
                        .header("X-User-Id", "1")
                        .param("startAt", startAt)
                        .param("endAt", endAt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].traceId", hasItem(row.getTraceId())));

        // updateLabel
        TraceLabelRequest label = new TraceLabelRequest(
                "RECOMMEND_MEAL",
                new SlotBundle(List.of("Lunch"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                null,
                "looks correct");
        mockMvc.perform(put(BASE + "/traces/{traceId}/label", row.getTraceId())
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(label)))
                .andExpect(status().isOk());

        // label persisted
        mockMvc.perform(get(BASE + "/traces/{traceId}", row.getTraceId())
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedIntent").value("RECOMMEND_MEAL"))
                .andExpect(jsonPath("$.labelNote").value("looks correct"))
                .andExpect(jsonPath("$.labeledBy").value(1));
    }

    @Test
    void findByTimeRange_startNotBeforeEnd_returns400() throws Exception {
        String startAt = LocalDateTime.now().format(ISO);
        String endAt = LocalDateTime.now().minusMinutes(5).format(ISO);

        mockMvc.perform(get(BASE + "/traces")
                        .header("X-User-Id", "1")
                        .param("startAt", startAt)
                        .param("endAt", endAt))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateLabel_unknownTraceId_returns400() throws Exception {
        TraceLabelRequest label = new TraceLabelRequest(null, null, null, "n/a");

        mockMvc.perform(put(BASE + "/traces/{traceId}/label", "trace_does_not_exist")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(label)))
                .andExpect(status().isBadRequest());
    }
}
