package com.mealmind.model;

import com.mealmind.enums.SourceMode;
import java.util.List;

/**
 * Layer1: search. For each constrained dimension, it asks, “Does this dish overlap with the query?”
 *         —JSON_OVERLAPS counts a match as long as there is a single tag match.
 *         This determines “which items make it into the candidate set.”
 * Retrieval request for the recommendation pipeline. Packs the four inputs
 * explicitly so the personal and public libraries are never queried together.
 */
public record MealSearchRequest(
        SourceMode sourceMode,       // required: which library to hit
        Long userId,                 // required when sourceMode == PERSONAL
        SlotBundle slots,            // normalized tags fed to JSON_OVERLAPS
        List<Long> excludeMealIds    // previous picks; filtered by the Rank layer, NOT here
) {
}