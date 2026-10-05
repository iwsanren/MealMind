package com.mealmind.dto.meal;

import com.mealmind.model.MealFacts;
import com.mealmind.model.SlotBundle;

import java.math.BigDecimal;
import java.util.List;

/**
 * Inbound payload for create/update: seven raw tag lists that collapse into a
 * normalized SlotBundle, plus optional per-serving facts. Omitted facts are
 * stored as NULL (unknown), and an update replaces the whole meal state.
 * Validation lives in MealService.
 */
public record MealRequest(
        String name,
        List<String> mealTime,
        List<String> mood,
        List<String> scene,
        List<String> healthGoal,
        List<String> cuisine,
        List<String> taste,
        List<String> convenience,
        BigDecimal price,
        BigDecimal proteinG,
        Integer calories,
        List<String> allergens
) {
    public SlotBundle toSlots() {
        // SlotBundle's compact constructor handles null / blank / duplicates.
        return new SlotBundle(mealTime, mood, scene, healthGoal, cuisine, taste, convenience);
    }

    public MealFacts toFacts() {
        return new MealFacts(price, proteinG, calories, allergens);
    }
}
