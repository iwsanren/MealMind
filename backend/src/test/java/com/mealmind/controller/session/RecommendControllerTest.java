package com.mealmind.controller.session;

import com.mealmind.dto.recommend.RecommendResponse;
import com.mealmind.dto.recommend.RecommendResponse.Source;
import com.mealmind.exception.MealException;
import com.mealmind.service.recommend.RecommendationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of POST /api/v1/sessions/{id}/recommend. The service is mocked. */
@WebMvcTest(RecommendController.class)
class RecommendControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    RecommendationService recommendationService;

    @Test
    void returnsTheResponseAndPassesUserSessionAndMessage() throws Exception {
        when(recommendationService.recommend("sess_1", 7L, "dinner", false)).thenReturn(
                new RecommendResponse("sess_1", Source.AGENT, null, "Good fit.", List.of(), "run_1"));

        mockMvc.perform(post("/api/v1/sessions/sess_1/recommend").header("X-User-Id", "7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"dinner\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("AGENT"))
                .andExpect(jsonPath("$.text").value("Good fit."))
                .andExpect(jsonPath("$.traceId").value("run_1"))
                .andExpect(jsonPath("$.meals").isEmpty());
    }

    @Test
    void modeRulesSwitchesTheAgentOffForThisRequestOnly() throws Exception {
        when(recommendationService.recommend(any(), any(), any(), eq(true))).thenReturn(
                new RecommendResponse("sess_1", Source.RULES, "DISABLED_BY_REQUEST", "ok", List.of(), null));

        mockMvc.perform(post("/api/v1/sessions/sess_1/recommend?mode=rules")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"dinner\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallbackReason").value("DISABLED_BY_REQUEST"));
        verify(recommendationService).recommend("sess_1", 1L, "dinner", true);   // X-User-Id defaults to 1
    }

    @Test
    void anUnknownModeIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/sess_1/recommend?mode=agent")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"dinner\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("mode must be auto or rules"));
    }

    @Test
    void businessErrorsBecomeBadRequestsWithAMessage() throws Exception {
        when(recommendationService.recommend(any(), any(), any(), eq(false)))
                .thenThrow(new MealException("Session not found for this user"));

        mockMvc.perform(post("/api/v1/sessions/nope/recommend")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"dinner\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Session not found for this user"));
    }
}
