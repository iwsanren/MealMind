package com.mealmind.service.meal;

import com.mealmind.enums.SourceMode;
import com.mealmind.exception.MealException;
import com.mealmind.model.MealSearchRequest;
import com.mealmind.model.SlotBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// test guards + the delegate call (including canonicalized hard constraints).
@ExtendWith(MockitoExtension.class)
class MealSearchServiceTest {

    @Mock
    MealService mealService;

    @InjectMocks
    MealSearchService mealSearchService;

    private static MealSearchRequest request(SourceMode mode, Long userId, SlotBundle slots,
                                             BigDecimal maxPrice, List<String> excludeAllergens) {
        return new MealSearchRequest(mode, userId, slots, List.of(), maxPrice, excludeAllergens);
    }

    @Test
    void rejectsNullSourceMode() {
        var request = request(null, 1L, SlotBundle.empty(), null, null);
        assertThatThrownBy(() -> mealSearchService.search(request))
                .isInstanceOf(MealException.class);
    }

    @Test
    void rejectsPersonalWithoutUserId() {
        var request = request(SourceMode.PERSONAL, null, SlotBundle.empty(), null, null);
        assertThatThrownBy(() -> mealSearchService.search(request))
                .isInstanceOf(MealException.class);
    }

    @Test
    void delegatesToMealServiceForValidRequest() {
        var slots = SlotBundle.empty();
        var request = request(SourceMode.PUBLIC, null, slots, null, null);
        when(mealService.search(SourceMode.PUBLIC, null, slots, null, List.of())).thenReturn(List.of());

        mealSearchService.search(request);

        // proves the guard passed and the call was forwarded unchanged (no allergens -> empty list)
        verify(mealService).search(SourceMode.PUBLIC, null, slots, null, List.of());
    }

    @Test
    void forwardsBudgetAndCanonicalizesAllergenTokens() {
        var slots = SlotBundle.empty();
        var budget = new BigDecimal("15.00");
        var request = request(SourceMode.PUBLIC, null, slots, budget, List.of(" Shellfish", "PEANUT", "shellfish"));
        when(mealService.search(SourceMode.PUBLIC, null, slots, budget, List.of("shellfish", "peanut")))
                .thenReturn(List.of());

        mealSearchService.search(request);

        verify(mealService).search(SourceMode.PUBLIC, null, slots, budget, List.of("shellfish", "peanut"));
    }

    @Test
    void rejectsNegativeBudget() {
        var request = request(SourceMode.PUBLIC, null, SlotBundle.empty(), new BigDecimal("-1"), null);
        assertThatThrownBy(() -> mealSearchService.search(request))
                .isInstanceOf(MealException.class)
                .hasMessageContaining("maxPrice");
    }

    @Test
    void rejectsAllergenOutsideVocabulary() {
        var request = request(SourceMode.PUBLIC, null, SlotBundle.empty(), null, List.of("peanut_butter"));
        assertThatThrownBy(() -> mealSearchService.search(request))
                .isInstanceOf(MealException.class)
                .hasMessageContaining("peanut_butter");
    }
}
