package com.mealmind.service.meal;

import com.mealmind.enums.SourceMode;
import com.mealmind.exception.MealException;
import com.mealmind.model.MealItem;
import com.mealmind.model.MealSearchRequest;
import com.mealmind.service.MealService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Recommendation pipeline layer 1: source-isolated recall.
 * Guards the request, then delegates to MealService.search (MySQL JSON_OVERLAPS).
 * Scoring and excludeMealIds filtering belong to later layers.
 */
@Service
public class MealSearchService {

    private final MealService mealService;


    public MealSearchService(MealService mealService) {
        this.mealService = mealService;
    }

    public List<MealItem> search(MealSearchRequest request) {
        if (request == null || request.sourceMode() == null) {
            throw new MealException("sourceMode must not be null");
        }
        if (request.sourceMode() == SourceMode.PERSONAL && request.userId() == null) {
            throw new MealException("userId is required for PERSONAL search");
        }
        // TODO (later step): invoked by the Orchestrator recommendation flow.
        return mealService.search(request.sourceMode(), request.userId(), request.slots());
    }
}