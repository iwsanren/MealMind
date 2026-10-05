package com.mealmind.controller.internal;

import com.mealmind.enums.SourceMode;
import com.mealmind.exception.MealException;
import com.mealmind.model.MealFacts;
import com.mealmind.model.MealItem;
import com.mealmind.model.MealRankRequest;
import com.mealmind.model.MealSearchRequest;
import com.mealmind.model.SlotBundle;
import com.mealmind.service.meal.MealRankService;
import com.mealmind.service.meal.MealSearchService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of POST /internal/v1/meals/search. Services are mocked: this does not test SQL or ranking logic. */
@WebMvcTest(InternalMealController.class)
class InternalMealControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    MealSearchService mealSearchService;

    @MockBean
    MealRankService mealRankService;

    private static MealItem meal(long id, double score) {
        return new MealItem(id, SourceMode.PUBLIC, null, "meal-" + id, SlotBundle.empty(),
                new MealFacts(new BigDecimal("12.50"), new BigDecimal("38.0"), 520, List.of("milk")), score);
    }

    private org.springframework.test.web.servlet.ResultActions search(String json) throws Exception {
        return mockMvc.perform(post("/internal/v1/meals/search").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void searchesThenRanksAndReturnsFacts() throws Exception {
        List<MealItem> candidates = List.of(meal(1, 0), meal(3, 0));
        when(mealSearchService.search(any())).thenReturn(candidates);
        when(mealRankService.rank(any())).thenReturn(List.of(meal(3, 0.5)));

        search("""
                {"sourceMode":"PUBLIC","healthGoal":["High Protein"],"excludeMealIds":[2],
                 "maxPrice":15,"excludeAllergens":["shellfish"]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.meals[0].id").value(3))
                .andExpect(jsonPath("$.meals[0].price").value(12.5))
                .andExpect(jsonPath("$.meals[0].proteinG").value(38.0))
                .andExpect(jsonPath("$.meals[0].allergens[0]").value("milk"))
                .andExpect(jsonPath("$.meals[0].matchScore").value(0.5));

        ArgumentCaptor<MealSearchRequest> search = ArgumentCaptor.forClass(MealSearchRequest.class);
        verify(mealSearchService).search(search.capture());
        assertThat(search.getValue().sourceMode()).isEqualTo(SourceMode.PUBLIC);
        assertThat(search.getValue().maxPrice()).isEqualByComparingTo("15");
        assertThat(search.getValue().excludeAllergens()).containsExactly("shellfish");
        assertThat(search.getValue().slots().healthGoal()).containsExactly("High Protein");

        // rank must receive exactly what search returned, plus the exclusion list
        ArgumentCaptor<MealRankRequest> rank = ArgumentCaptor.forClass(MealRankRequest.class);
        verify(mealRankService).rank(rank.capture());
        assertThat(rank.getValue().candidates()).isEqualTo(candidates);
        assertThat(rank.getValue().excludeMealIds()).containsExactly(2L);
    }

    @Test
    void emptyResultIsExplicit() throws Exception {
        when(mealSearchService.search(any())).thenReturn(List.of());
        when(mealRankService.rank(any())).thenReturn(List.of());

        search("{\"sourceMode\":\"public\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.meals").isEmpty());
    }

    @Test
    void missingOrUnknownSourceModeIsA400() throws Exception {
        search("{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("sourceMode")));
        search("{\"sourceMode\":\"EVERYWHERE\"}").andExpect(status().isBadRequest());
    }

    @Test
    void serviceValidationFailuresBecome400() throws Exception {
        // the real guards (PERSONAL needs userId, allergen vocabulary, negative budget) live in MealSearchService
        when(mealSearchService.search(any())).thenThrow(new MealException("userId is required for PERSONAL search"));

        search("{\"sourceMode\":\"PERSONAL\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("userId is required for PERSONAL search"));
    }
}
