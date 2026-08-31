package com.mealmind.dto.meal;

import com.mealmind.model.SlotBundle;
import java.util.List;

/**
 * Inbound payload for create/update: seven raw tag lists that collapse into a
 * normalized SlotBundle. No validation here yet (later step).
 */
public record MealRequest(
        String name,
        List<String> mealTime,
        List<String> mood,
        List<String> scene,
        List<String> healthGoal,
        List<String> cuisine,
        List<String> taste,
        List<String> convenience
) {
    public SlotBundle toSlots() {
        // SlotBundle's compact constructor handles null / blank / duplicates.
        return new SlotBundle(mealTime, mood, scene, healthGoal, cuisine, taste, convenience);
    }
}