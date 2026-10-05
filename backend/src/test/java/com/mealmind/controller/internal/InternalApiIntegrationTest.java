package com.mealmind.controller.internal;

import com.mealmind.dto.feedback.FeedbackRequest;
import com.mealmind.service.feedback.FeedbackService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full stack over the real database: internal controllers -> services -> mappers -> MySQL.
 * Relies on the five illustrative seed meals from V1 + V4 (public ids 1-4, see notes/agent/data-model.md).
 * Everything written here is rolled back at the end of each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InternalApiIntegrationTest {

    // A user id no real or seed data uses, so feedback and traces written here are isolated.
    private static final long USER = 987_654_322L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    FeedbackService feedbackService;

    private ResultActions search(String json) throws Exception {
        return mockMvc.perform(post("/internal/v1/meals/search").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void budgetIsEnforcedInSqlOnRealSeedData() throws Exception {
        // only the oats ($6.50) cost 10 or less among the public seed meals
        search("{\"sourceMode\":\"PUBLIC\",\"maxPrice\":10}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.meals[0].name").value("Overnight Oats with Berries"))
                .andExpect(jsonPath("$.meals[*].price", everyItem(lessThanOrEqualTo(10.0))));
    }

    @Test
    void allergenFilterRemovesMealsThatContainThem() throws Exception {
        // the caesar salad contains fish; the other seeds do not
        search("{\"sourceMode\":\"PUBLIC\",\"excludeAllergens\":[\"fish\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meals[?(@.name=='Grilled Chicken Caesar Salad')]").isEmpty())
                .andExpect(jsonPath("$.meals[?(@.name=='Margherita Pizza')]").isNotEmpty());
        // every seed meal contains milk, so excluding it leaves nothing - and that must be explicit
        search("{\"sourceMode\":\"PUBLIC\",\"excludeAllergens\":[\"milk\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void tagsBudgetAndAllergensCombine() throws Exception {
        search("{\"sourceMode\":\"PUBLIC\",\"healthGoal\":[\"High Protein\"],\"maxPrice\":15,\"excludeAllergens\":[\"shellfish\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meals[0].name").value("Grilled Chicken Caesar Salad"))
                .andExpect(jsonPath("$.meals[0].proteinG").value(38.0));
    }

    @Test
    void invalidInputsAreFourHundreds() throws Exception {
        search("{\"sourceMode\":\"PERSONAL\"}").andExpect(status().isBadRequest());
        search("{\"sourceMode\":\"PUBLIC\",\"excludeAllergens\":[\"peanut_butter\"]}").andExpect(status().isBadRequest());
        search("{\"sourceMode\":\"PUBLIC\",\"maxPrice\":-1}").andExpect(status().isBadRequest());
    }

    @Test
    void recentFeedbackIsNewestFirstAndSkipsDeletedMeals() throws Exception {
        feedbackService.save(USER, new FeedbackRequest("sess_it", 2L, "dislike", 2, null));
        feedbackService.save(USER, new FeedbackRequest("sess_it", 999_999_999L, "LIKE", 5, null)); // meal does not exist
        feedbackService.save(USER, new FeedbackRequest("sess_it", 1L, "LIKE", 5, null));

        mockMvc.perform(get("/internal/v1/users/" + USER + "/recent-feedback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.items[0].mealName").value("Grilled Chicken Caesar Salad"))
                .andExpect(jsonPath("$.items[0].action").value("LIKE"))
                .andExpect(jsonPath("$.items[1].mealName").value("Classic Cheeseburger with Fries"))
                .andExpect(jsonPath("$.items[1].action").value("DISLIKE"));

        mockMvc.perform(get("/internal/v1/users/" + USER + "/recent-feedback?limit=0")).andExpect(status().isBadRequest());
    }

    @Test
    void traceCanBeWrittenReadBackAndNotDuplicated() throws Exception {
        String traceId = "trace_internal_it_" + UUID.randomUUID().toString().replace("-", "");
        String body = "{\"traceId\":\"" + traceId + "\",\"sessionId\":\"sess_it\",\"userId\":" + USER
                + ",\"status\":\"SUCCESS\",\"durationMs\":42,\"events\":[{\"round\":1,\"tool\":\"search_meals\"},{\"round\":2}]}";

        mockMvc.perform(post("/internal/v1/traces").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventCount").value(2));

        // read back through the existing debug API the Trace page uses
        mockMvc.perform(get("/api/v1/debug/traces/" + traceId).header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventCount").value(2))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.traceJson", containsString("search_meals")));

        mockMvc.perform(post("/internal/v1/traces").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("already exists")));
    }
}
