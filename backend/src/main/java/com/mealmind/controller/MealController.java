package com.mealmind.controller;

import com.mealmind.constants.MealMindConstants;
import com.mealmind.dto.meal.MealRequest;
import com.mealmind.dto.meal.MealResponse;
import com.mealmind.service.meal.MealService;
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

    // Public library is shared and read-only here; no user header needed.
    @GetMapping("/public")
    public List<MealResponse> findPublic() {
        return mealService.findPublicMeals().stream()
                .map(MealResponse::from)
                .toList();
    }

    // Update an existing personal meal; body carries the full new state.
    @PutMapping("/personal/{mealId}")
    public MealResponse updatePersonal(
            @RequestHeader(value = MealMindConstants.USER_ID, defaultValue = "1") Long userId,
            @PathVariable Long mealId,
            @RequestBody MealRequest request) {
        return MealResponse.from(mealService.updatePersonalMeal(userId, mealId, request));
    }

    // Delete a personal meal. Service raises MealException (-> 400) when 0 rows match,
    // i.e. the id does not exist or is not owned by this user.
    @DeleteMapping("/personal/{mealId}")
    public void deletePersonal(
            @RequestHeader(value = MealMindConstants.USER_ID, defaultValue = "1") Long userId,
            @PathVariable Long mealId) {
        mealService.deletePersonalMeal(userId, mealId);
    }



    // PUT /personal/{mealId} + DELETE /personal/{mealId} follow the same header pattern
    // (@PathVariable Long mealId); GET /public takes no header -> mealService.findPublicMeals().
}