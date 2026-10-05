package com.mealmind.dto.internal;

import java.math.BigDecimal;
import java.util.List;

/**
 * Body of POST /internal/v1/meals/search. sourceMode is a String (not the enum)
 * so a bad value becomes a 400 from our own validation instead of a 500 from
 * the JSON layer. Tag lists are the seven slot dimensions; omit or leave
 * empty for "no constraint on that dimension".
 */
public record InternalMealSearchRequest(
        String sourceMode,
        Long userId,
        List<String> mealTime,
        List<String> mood,
        List<String> scene,
        List<String> healthGoal,
        List<String> cuisine,
        List<String> taste,
        List<String> convenience,
        List<Long> excludeMealIds,
        BigDecimal maxPrice,
        List<String> excludeAllergens
) {
}
