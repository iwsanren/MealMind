package com.mealmind.model;

import com.mealmind.enums.SourceMode;
import java.util.List;

/**
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