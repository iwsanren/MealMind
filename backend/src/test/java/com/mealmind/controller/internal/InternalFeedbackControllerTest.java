package com.mealmind.controller.internal;

import com.mealmind.exception.MealException;
import com.mealmind.service.feedback.RecentFeedbackService;
import com.mealmind.service.feedback.RecentFeedbackService.RecentFeedback;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of GET /internal/v1/users/{userId}/recent-feedback. */
@WebMvcTest(InternalFeedbackController.class)
class InternalFeedbackControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    RecentFeedbackService recentFeedbackService;

    @Test
    void returnsItemsWithTheExplicitUserId() throws Exception {
        when(recentFeedbackService.findRecent(7L, 5)).thenReturn(List.of(
                new RecentFeedback(2L, "Classic Cheeseburger with Fries", "DISLIKE", LocalDateTime.of(2026, 10, 5, 12, 0))));

        mockMvc.perform(get("/internal/v1/users/7/recent-feedback?limit=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].mealId").value(2))
                .andExpect(jsonPath("$.items[0].mealName").value("Classic Cheeseburger with Fries"))
                .andExpect(jsonPath("$.items[0].action").value("DISLIKE"))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-10-05T12:00:00"));
    }

    @Test
    void noFeedbackIsAnExplicitEmptyList() throws Exception {
        when(recentFeedbackService.findRecent(7L, null)).thenReturn(List.of());

        mockMvc.perform(get("/internal/v1/users/7/recent-feedback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
        verify(recentFeedbackService).findRecent(7L, null);
    }

    @Test
    void invalidLimitIsA400() throws Exception {
        when(recentFeedbackService.findRecent(7L, 0)).thenThrow(new MealException("limit must be between 1 and 50"));

        mockMvc.perform(get("/internal/v1/users/7/recent-feedback?limit=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("limit must be between 1 and 50"));
    }
}
