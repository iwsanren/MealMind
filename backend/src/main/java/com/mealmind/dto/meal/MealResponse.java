package com.mealmind.dto.meal;

import com.mealmind.enums.SourceMode;
import com.mealmind.model.MealFacts;
import com.mealmind.model.MealItem;
import com.mealmind.model.SlotBundle;

import java.math.BigDecimal;
import java.util.List;

/**
 * Outbound view: flattens the domain MealItem's SlotBundle back into seven
 * lists, adds the per-serving facts (null = unknown; allergens null = unknown,
 * empty = known to contain none) and carries matchScore (0 for CRUD responses).
 */
public record MealResponse(
        Long id,
        SourceMode sourceType,
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
        List<String> allergens,
        double matchScore
) {
    public static MealResponse from(MealItem item) {
        SlotBundle s = item.slots();
        MealFacts f = item.facts() == null ? MealFacts.unknown() : item.facts();
        return new MealResponse(
                item.id(), item.sourceType(), item.name(),
                s.mealTime(), s.mood(), s.scene(), s.healthGoal(),
                s.cuisine(), s.taste(), s.convenience(),
                f.price(), f.proteinG(), f.calories(), f.allergens(),
                item.matchScore()
        );
    }
}
