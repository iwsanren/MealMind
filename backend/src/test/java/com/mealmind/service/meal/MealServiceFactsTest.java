package com.mealmind.service.meal;

import com.mealmind.dto.meal.MealRequest;
import com.mealmind.entity.MealItemRow;
import com.mealmind.exception.MealException;
import com.mealmind.mapper.MealMapper;
import com.mealmind.model.MealItem;
import com.mealmind.util.JsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Validation and row mapping for the price / protein / calories / allergens facts. */
class MealServiceFactsTest {

    private final MealMapper mealMapper = mock(MealMapper.class);
    private final MealService mealService = new MealService(mealMapper, new JsonService(new ObjectMapper()));

    private static MealRequest request(BigDecimal price, BigDecimal protein, Integer calories, List<String> allergens) {
        return new MealRequest("Test bowl", List.of("Lunch"), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), price, protein, calories, allergens);
    }

    @Test
    void storesFactsAndKeepsEmptyAllergenListAsEmptyJsonArray() {
        mealService.createPersonalMeal(1L, request(new BigDecimal("9.50"), new BigDecimal("30.0"), 600, List.of()));

        ArgumentCaptor<MealItemRow> row = ArgumentCaptor.forClass(MealItemRow.class);
        verify(mealMapper).insert(row.capture());
        assertThat(row.getValue().getPrice()).isEqualByComparingTo("9.50");
        assertThat(row.getValue().getAllergens()).isEqualTo("[]"); // known to contain none
    }

    @Test
    void unknownAllergensStayNullInsteadOfBecomingEmpty() {
        mealService.createPersonalMeal(1L, request(null, null, null, null));

        ArgumentCaptor<MealItemRow> row = ArgumentCaptor.forClass(MealItemRow.class);
        verify(mealMapper).insert(row.capture());
        assertThat(row.getValue().getAllergens()).isNull(); // unknown must not look like "safe"
        assertThat(row.getValue().getPrice()).isNull();
    }

    @Test
    void readingARowWithNullAllergensKeepsThemUnknown() {
        MealItemRow row = new MealItemRow();
        row.setId(7L);
        row.setSourceType("PERSONAL");
        row.setOwnerUserId(1L);
        row.setName("Mystery stew");
        row.setMealTime("[\"Dinner\"]");
        row.setMood("[]");
        row.setScene("[]");
        row.setHealthGoal("[]");
        row.setCuisine("[]");
        row.setTaste("[]");
        row.setConvenience("[]");
        row.setAllergens(null);
        when(mealMapper.findPersonalMeals(1L)).thenReturn(List.of(row));

        MealItem item = mealService.findPersonalMeals(1L).get(0);

        assertThat(item.facts().allergens()).isNull();
    }

    @Test
    void rejectsNegativeFacts() {
        assertThatThrownBy(() -> mealService.createPersonalMeal(1L, request(new BigDecimal("-0.01"), null, null, null)))
                .isInstanceOf(MealException.class).hasMessageContaining("price");
        assertThatThrownBy(() -> mealService.createPersonalMeal(1L, request(null, new BigDecimal("-1"), null, null)))
                .isInstanceOf(MealException.class).hasMessageContaining("proteinG");
        assertThatThrownBy(() -> mealService.createPersonalMeal(1L, request(null, null, -5, null)))
                .isInstanceOf(MealException.class).hasMessageContaining("calories");
    }

    @Test
    void rejectsAllergenOutsideVocabulary() {
        assertThatThrownBy(() -> mealService.createPersonalMeal(1L, request(null, null, null, List.of("gluten"))))
                .isInstanceOf(MealException.class).hasMessageContaining("gluten");
        // nothing may reach the database when validation fails
        org.mockito.Mockito.verify(mealMapper, org.mockito.Mockito.never()).insert(any());
    }
}
