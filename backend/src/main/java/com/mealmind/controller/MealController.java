package com.mealmind.controller;

import com.mealmind.converter.MealConverter;
import com.mealmind.dto.meal.CreateMealRequest;
import com.mealmind.dto.meal.MealResponse;
import com.mealmind.service.MealService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class MealController {

    private final MealService mealService;
    private final MealConverter mealConverter;

    public MealController(MealService mealService, MealConverter mealConverter) {
        this.mealService = mealService;
        this.mealConverter = mealConverter;
    }

    @PostMapping("/api/v1/meals")
    public MealResponse createMeal(@RequestBody CreateMealRequest request) {
        return mealConverter.toResponse(
                mealService.createMeal(mealConverter.toMealItem(request))
        );
    }

    @GetMapping("/api/v1/meals")
    public List<MealResponse> getMeals() {
        return mealService.getMeals().stream()
                .map(mealConverter::toResponse)
                .toList();
    }
}
