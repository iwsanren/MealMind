package com.mealmind.dto.trace;

import com.mealmind.model.SlotBundle;

/**
 * Human-supplied "correct answer" for one trace, used later to evaluate/tune
 * the recommendation pipeline. expectedIntent/expectedClarifyAction are plain
 * strings for now - MealMind doesn't have Intent/ClarifyAction enums yet
 * (Part 2 introduces them); upgrade these two fields to the real enums once
 * they exist. expectedSlots reuses the existing SlotBundle as-is.
 */
public record TraceLabelRequest(
        String expectedIntent,
        SlotBundle expectedSlots,
        String expectedClarifyAction,
        String labelNote
) {
}
