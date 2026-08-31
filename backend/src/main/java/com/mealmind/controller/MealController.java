package com.mealmind.controller;

import com.mealmind.constants.MealMindConstants;
import com.mealmind.dto.meal.CreateMealRequest;
import com.mealmind.dto.meal.MealRequest;
import com.mealmind.dto.meal.MealResponse;
import com.mealmind.service.MealService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/meals")
public class MealController {

    private final MealService mealService;

    public MealController(MealService mealService) {
        this.mealService = mealService;
    }

    // X-User-Id header identifies the caller; defaults to "1" until auth exists (later step).
    @GetMapping("/personal")
    public List<MealResponse> findPersonal(
            @RequestHeader(value = MealMindConstants.USER_ID, defaultValue = "1") Long userId) {
        return mealService.findPersonalMeals(userId).stream().map(MealResponse::from).toList();
    }

    @PostMapping("/personal")
    public MealResponse createPersonal(
            @RequestHeader(value = MealMindConstants.USER_ID, defaultValue = "1") Long userId,
            @RequestBody MealRequest request) {
        return MealResponse.from(mealService.createPersonalMeal(userId, request));
    }

    // PUT /personal/{mealId} + DELETE /personal/{mealId} follow the same header pattern
    // (@PathVariable Long mealId); GET /public takes no header -> mealService.findPublicMeals().
}