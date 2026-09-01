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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// test guards + one delegate call.
@ExtendWith(MockitoExtension.class)
class MealSearchServiceTest {

    @Mock
    MealService mealService;

    @InjectMocks
    MealSearchService mealSearchService;

    @Test
    void rejectsNullSourceMode() {
        var request = new MealSearchRequest(null, 1L, SlotBundle.empty(), List.of());
        assertThatThrownBy(() -> mealSearchService.search(request))
                .isInstanceOf(MealException.class);
    }

    @Test
    void rejectsPersonalWithoutUserId() {
        var request = new MealSearchRequest(SourceMode.PERSONAL, null, SlotBundle.empty(), List.of());
        assertThatThrownBy(() -> mealSearchService.search(request))
                .isInstanceOf(MealException.class);
    }

    @Test
    void delegatesToMealServiceForValidRequest() {
        var slots = SlotBundle.empty();
        var request = new MealSearchRequest(SourceMode.PUBLIC, null, slots, List.of());
        when(mealService.search(SourceMode.PUBLIC, null, slots)).thenReturn(List.of());

        mealSearchService.search(request);

        // proves the guard passed and the call was forwarded unchanged
        verify(mealService).search(SourceMode.PUBLIC, null, slots);
    }
}