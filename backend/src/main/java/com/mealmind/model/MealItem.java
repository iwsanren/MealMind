package com.mealmind.model;

import com.mealmind.enums.SourceMode;

/**
 * Domain view of a meal: tags already parsed into a SlotBundle, plus a
 * matchScore that the recommendation/ranking step fills later (0 for plain CRUD).
 * MyBatis never builds this type (only MealItemRow), so it stays immutable.
 */
public record MealItem(
        Long id,
        SourceMode sourceType,
        Long ownerUserId,
        String name,
        SlotBundle slots,
        double matchScore
) {
    // Data-shape
    // TODO: compute a score
    public MealItem withMatchScore(double score) {
        return new MealItem(id, sourceType, ownerUserId, name, slots, score);
    }
}