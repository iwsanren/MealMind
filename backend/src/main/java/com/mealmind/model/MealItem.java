package com.mealmind.model;

import com.mealmind.enums.SourceMode;

/**
 * Domain view of a meal: tags already parsed into a SlotBundle, checkable
 * facts (price, protein, calories, allergens) in MealFacts, plus a
 * matchScore that the recommendation/ranking step fills later (0 for plain CRUD).
 * MyBatis never builds this type (only MealItemRow), so it stays immutable.
 * (Ingredients are still not modeled.)
 */
public record MealItem(
        Long id,
        SourceMode sourceType,
        Long ownerUserId,
        String name,
        SlotBundle slots,
        MealFacts facts,
        double matchScore
) {
    // Data-shape
    // TODO: compute a score
    public MealItem withMatchScore(double score) {
        return new MealItem(id, sourceType, ownerUserId, name, slots, facts, score);
    }
}
