package com.mealmind.dto.meal;

import java.util.List;

public record MealResponse(
        long id,
        String name,
        Double price,
        List<String> tags
) {
}
