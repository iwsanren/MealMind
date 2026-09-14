package com.mealmind.service.clarify;

import com.mealmind.model.SlotBundle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClarifyRuleServiceTest {

    private final ClarifyRuleService clarifyRuleService = new ClarifyRuleService();

    // helper: only the dimensions relevant to a test are filled, the rest default empty
    private static SlotBundle bundle(List<String> mealTime, List<String> healthGoal,
                                      List<String> cuisine, List<String> taste,
                                      List<String> scene, List<String> convenience) {
        return new SlotBundle(mealTime, List.of(), scene, healthGoal, cuisine, taste, convenience);
    }

    @Test
    void missingMealTimeIsAlwaysFlagged() {
        SlotBundle slots = bundle(List.of(), List.of("Balanced"),
                List.of("Western"), List.of("Savory"), List.of("Home"), List.of("Quick"));

        assertThat(clarifyRuleService.missingSlots(slots)).contains("mealTime");
        assertThat(clarifyRuleService.hasEnoughSlots(slots)).isFalse();
    }

    @Test
    void missingHealthGoalIsFlaggedWhenNoOtherPreferenceIsExpressed() {
        SlotBundle slots = bundle(List.of("Lunch"), List.of(),
                List.of(), List.of(), List.of(), List.of());

        assertThat(clarifyRuleService.missingSlots(slots)).containsExactly("healthGoal");
        assertThat(clarifyRuleService.hasEnoughSlots(slots)).isFalse();
    }

    @Test
    void missingHealthGoalIsSkippedWhenAnyStrongPreferenceIsExpressed() {
        SlotBundle withCuisine = bundle(List.of("Lunch"), List.of(),
                List.of("Western"), List.of(), List.of(), List.of());
        SlotBundle withTaste = bundle(List.of("Lunch"), List.of(),
                List.of(), List.of("Savory"), List.of(), List.of());

        assertThat(clarifyRuleService.missingSlots(withCuisine)).doesNotContain("healthGoal");
        assertThat(clarifyRuleService.missingSlots(withTaste)).doesNotContain("healthGoal");
    }

    @Test
    void allSevenDimensionsFilledMeansNothingIsMissing() {
        SlotBundle slots = new SlotBundle(
                List.of("Lunch"), List.of("Happy"), List.of("Home"), List.of("Balanced"),
                List.of("Western"), List.of("Savory"), List.of("Quick"));

        assertThat(clarifyRuleService.missingSlots(slots)).isEmpty();
        assertThat(clarifyRuleService.hasEnoughSlots(slots)).isTrue();
    }

    @Test
    void nullSlotsAreTreatedAsEmptyNotAnError() {
        assertThat(clarifyRuleService.missingSlots(null)).contains("mealTime", "healthGoal");
        assertThat(clarifyRuleService.hasEnoughSlots(null)).isFalse();
    }

    @Test
    void fallbackQuestionPicksTheBranchMatchingMissingSlots() {
        assertThat(clarifyRuleService.fallbackQuestion(List.of()))
                .isEqualTo("Would you rather go by flavor, or by a goal like light or filling?");
        assertThat(clarifyRuleService.fallbackQuestion(List.of("mealTime")))
                .isEqualTo("Is this for breakfast, lunch, or dinner?");
        assertThat(clarifyRuleService.fallbackQuestion(List.of("healthGoal")))
                .isEqualTo("Would you rather go light, filling, or just pick by flavor?");
        // mealTime takes priority when both are missing
        assertThat(clarifyRuleService.fallbackQuestion(List.of("mealTime", "healthGoal")))
                .isEqualTo("Is this for breakfast, lunch, or dinner?");
        // defensive branch: a non-empty list naming neither known dimension
        assertThat(clarifyRuleService.fallbackQuestion(List.of("scene")))
                .isEqualTo("Just to confirm - what matters most this time: flavor, a health goal, or convenience?");
    }
}
