package com.mealmind.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Checkable facts about one meal, all per serving. Every field may be null,
 * and null means UNKNOWN - never "zero" or "safe":
 * - price null: the budget filter excludes this meal.
 * - allergens null: unknown allergen content, so an allergen filter excludes
 *   this meal. An empty list means "known to contain none".
 */
public record MealFacts(
        BigDecimal price,       // USD
        BigDecimal proteinG,    // grams
        Integer calories,       // kcal
        List<String> allergens  // Allergen tokens; null = unknown, empty = none
) {

    // Normalizes tokens but keeps null as null (SlotBundle turns null into an empty list; here that would lose "unknown").
    public MealFacts {
        if (allergens != null) {
            allergens = allergens.stream()
                    .filter(a -> a != null && !a.isBlank())
                    .map(a -> a.trim().toLowerCase(Locale.ROOT))
                    .distinct()
                    .toList();
        }
    }

    /** Nothing is known about this meal. */
    public static MealFacts unknown() {
        return new MealFacts(null, null, null, null);
    }
}
