package com.mealmind.model;

import com.mealmind.enums.SourceMode;

import java.math.BigDecimal;
import java.util.List;

/**
 * Layer1: search. For each constrained dimension, it asks, “Does this dish overlap with the query?”
 *         —JSON_OVERLAPS counts a match as long as there is a single tag match.
 *         This determines “which items make it into the candidate set.”
 * Retrieval request for the recommendation pipeline. Packs the inputs
 * explicitly so the personal and public libraries are never queried together.
 *
 * maxPrice and excludeAllergens are HARD constraints applied in SQL, before the
 * result LIMIT, so they can never be "outvoted" by tag overlap. Unknown facts
 * fail closed: a meal with no price is dropped when maxPrice is set, and a meal
 * with unknown allergens is dropped when excludeAllergens is non-empty.
 */
public record MealSearchRequest(
        SourceMode sourceMode,         // required: which library to hit
        Long userId,                   // required when sourceMode == PERSONAL
        SlotBundle slots,              // normalized tags fed to JSON_OVERLAPS
        List<Long> excludeMealIds,     // previous picks; filtered by the Rank layer, NOT here
        BigDecimal maxPrice,           // optional hard budget cap (USD, inclusive)
        List<String> excludeAllergens  // optional Allergen tokens the meal must not contain
) {
}
