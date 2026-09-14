package com.mealmind.service.slot;

import com.mealmind.model.SlotBundle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlotMergeServiceTest {

    private final SlotMergeService mergeService = new SlotMergeService();

    // helper: only mealTime/mood are ever filled in these tests, the rest default empty
    private static SlotBundle bundle(List<String> mealTime, List<String> mood) {
        return new SlotBundle(mealTime, mood, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void keepsHistoryWhenCurrentDimensionIsEmpty() {
        SlotBundle history = bundle(List.of("Lunch"), List.of());
        SlotBundle current = bundle(List.of(), List.of());

        SlotBundle merged = mergeService.merge(history, current);

        assertThat(merged.mealTime()).containsExactly("Lunch");
    }

    @Test
    void usesCurrentWhenHistoryDimensionIsEmpty() {
        SlotBundle history = bundle(List.of(), List.of());
        SlotBundle current = bundle(List.of(), List.of("Happy"));

        SlotBundle merged = mergeService.merge(history, current);

        assertThat(merged.mood()).containsExactly("Happy");
    }

    @Test
    void currentOverridesHistoryWhenBothDimensionsAreFilled() {
        SlotBundle history = bundle(List.of("Breakfast"), List.of("Tired"));
        SlotBundle current = bundle(List.of("Dinner"), List.of("Calm"));

        SlotBundle merged = mergeService.merge(history, current);

        assertThat(merged.mealTime()).containsExactly("Dinner");
        assertThat(merged.mood()).containsExactly("Calm");
    }

    @Test
    void bothNullProducesAnEmptyBundle() {
        SlotBundle merged = mergeService.merge(null, null);

        assertThat(merged.isEmpty()).isTrue();
    }

    @Test
    void bothEmptyProducesAnEmptyBundle() {
        SlotBundle merged = mergeService.merge(SlotBundle.empty(), SlotBundle.empty());

        assertThat(merged.isEmpty()).isTrue();
    }
}
