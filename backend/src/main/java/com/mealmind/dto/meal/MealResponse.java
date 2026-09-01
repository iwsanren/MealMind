package com.mealmind.dto.meal;

import com.mealmind.enums.SourceMode;
import com.mealmind.model.MealItem;
import com.mealmind.model.SlotBundle;
import java.util.List;

/**
 * Outbound view: flattens the domain MealItem's SlotBundle back into seven
 * lists and carries matchScore (0 for CRUD responses).
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
        double matchScore
) {
    public static MealResponse from(MealItem item) {
        SlotBundle s = item.slots();
        return new MealResponse(
                item.id(), item.sourceType(), item.name(),
                s.mealTime(), s.mood(), s.scene(), s.healthGoal(),
                s.cuisine(), s.taste(), s.convenience(),
                item.matchScore()
        );
    }
}