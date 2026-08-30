package com.mealmind.model;

import java.util.List;

/**
 * Immutable carrier for the 7 tag dimensions describing a meal context.
 * Every component is normalized at construction: null list -> empty list,
 * blank entries dropped, values trimmed and de-duplicated (first wins).
 */
public record SlotBundle(
        List<String> mealTime,
        List<String> mood,
        List<String> scene,
        List<String> healthGoal,
        List<String> cuisine,
        List<String> taste,
        List<String> convenience
) {

    // Compact constructor: runs before the record fields are assigned.
    public SlotBundle {
        mealTime = normalize(mealTime);
        mood = normalize(mood);
        scene = normalize(scene);
        healthGoal = normalize(healthGoal);
        cuisine = normalize(cuisine);
        taste = normalize(taste);
        convenience = normalize(convenience);
    }

    /** A bundle with all seven dimensions empty. */
    public static SlotBundle empty() {
        return new SlotBundle(List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
    }

    /** True when no dimension carries any tag. */
    public boolean isEmpty() {
        return mealTime.isEmpty()
                && mood.isEmpty()
                && scene.isEmpty()
                && healthGoal.isEmpty()
                && cuisine.isEmpty()
                && taste.isEmpty()
                && convenience.isEmpty();
    }

    // Drop null/blank, trim, keep first occurrence. Returns an unmodifiable list.
    private static List<String> normalize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}