package com.mealmind.controller.internal;

import com.mealmind.dto.internal.InternalMealSearchRequest;
import com.mealmind.dto.internal.InternalMealSearchResponse;
import com.mealmind.dto.meal.MealResponse;
import com.mealmind.enums.SourceMode;
import com.mealmind.exception.MealException;
import com.mealmind.model.MealItem;
import com.mealmind.model.MealRankRequest;
import com.mealmind.model.MealSearchRequest;
import com.mealmind.model.SlotBundle;
import com.mealmind.service.meal.MealRankService;
import com.mealmind.service.meal.MealSearchService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Internal API for ai-service: recall (search) then rank in one call, so the
 * agent never has to haul 50 candidates back and forth. There is no auth on
 * /internal/** yet (see notes/agent/internal-api.md).
 */
@RestController
@RequestMapping("/internal/v1/meals")
public class InternalMealController {

    private final MealSearchService mealSearchService;
    private final MealRankService mealRankService;

    public InternalMealController(MealSearchService mealSearchService, MealRankService mealRankService) {
        this.mealSearchService = mealSearchService;
        this.mealRankService = mealRankService;
    }

    @PostMapping("/search")
    public InternalMealSearchResponse search(@RequestBody InternalMealSearchRequest request) {
        if (request == null) {
            throw new MealException("Request body is required");
        }
        SlotBundle slots = new SlotBundle(request.mealTime(), request.mood(), request.scene(),
                request.healthGoal(), request.cuisine(), request.taste(), request.convenience());
        List<Long> excludeIds = request.excludeMealIds() == null ? List.of() : request.excludeMealIds();

        List<MealItem> candidates = mealSearchService.search(new MealSearchRequest(
                parseSourceMode(request.sourceMode()), request.userId(), slots, excludeIds,
                request.maxPrice(), request.excludeAllergens()));
        List<MealResponse> ranked = mealRankService.rank(new MealRankRequest(candidates, slots, excludeIds))
                .stream().map(MealResponse::from).toList();
        return new InternalMealSearchResponse(ranked.size(), ranked);
    }

    private static SourceMode parseSourceMode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new MealException("sourceMode is required (PERSONAL or PUBLIC)");
        }
        try {
            return SourceMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new MealException("sourceMode must be PERSONAL or PUBLIC");
        }
    }
}
