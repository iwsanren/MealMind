package com.mealmind.dto.internal;

import com.mealmind.dto.meal.MealResponse;

import java.util.List;

/** Ranked candidates (best first, at most 10) with a count so an empty result is explicit. */
public record InternalMealSearchResponse(int count, List<MealResponse> meals) {
}
