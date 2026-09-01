package com.mealmind.service.meal;

import com.mealmind.enums.SourceMode;
import com.mealmind.model.MealItem;
import com.mealmind.model.MealRankRequest;
import com.mealmind.model.SlotBundle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MealRankServiceTest {

    private final MealRankService rankService = new MealRankService();

    // helper: a meal constrained only on mealTime + healthGoal
    private static MealItem meal(long id, List<String> mealTime, List<String> healthGoal) {
        SlotBundle slots = new SlotBundle(mealTime, List.of(), List.of(),
                healthGoal, List.of(), List.of(), List.of());
        return new MealItem(id, SourceMode.PUBLIC, null, "meal-" + id, slots, 0d);
    }

    @Test
    void ranksByDegreeOfSatisfactionAndDropsExcluded() {
        SlotBundle query = new SlotBundle(List.of("Lunch", "Dinner"), List.of(), List.of(),
                List.of("High Protein", "Light"), List.of(), List.of(), List.of());

        MealItem a = meal(1, List.of("Lunch", "Dinner"), List.of("High Protein", "Light", "Low Fat")); // 2/2 + 2/2 -> 2.0/7
        MealItem b = meal(2, List.of("Dinner"),          List.of("High Protein"));                     // 1/2 + 1/2 -> 1.0/7
        MealItem c = meal(3, List.of("Lunch", "Dinner"), List.of("High Protein", "Light"));      // excluded by id

        List<MealItem> ranked = rankService.rank(
                new MealRankRequest(List.of(a, b, c), query, List.of(3L)));

        assertThat(ranked).extracting(MealItem::id).containsExactly(1L, 2L);
        assertThat(ranked.get(0).matchScore()).isCloseTo(2.0 / 7.0, within(1e-9));
        assertThat(ranked.get(1).matchScore()).isCloseTo(1.0 / 7.0, within(1e-9));
    }
}
