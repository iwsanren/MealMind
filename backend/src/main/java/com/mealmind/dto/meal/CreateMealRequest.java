package com.mealmind.dto.meal;

import java.util.List;

public record CreateMealRequest(
        String name,
        Double price,
        List<String> tags
) {
}
