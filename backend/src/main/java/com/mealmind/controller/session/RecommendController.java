package com.mealmind.controller.session;

import com.mealmind.constants.MealMindConstants;
import com.mealmind.dto.recommend.RecommendRequest;
import com.mealmind.dto.recommend.RecommendResponse;
import com.mealmind.exception.MealException;
import com.mealmind.service.recommend.RecommendationService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/recommend")
public class RecommendController {

    private final RecommendationService recommendationService;

    public RecommendController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    /**
     * mode=rules skips the agent for this one request (handy for comparing the two paths); there is deliberately no
     * way to turn the agent ON per request: that is the server's feature flag (mealmind.agent.enabled).
     */
    @PostMapping
    public RecommendResponse recommend(
            @RequestHeader(value = MealMindConstants.USER_ID, defaultValue = "1") Long userId,
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "auto") String mode,
            @RequestBody RecommendRequest request) {
        if (!mode.equals("auto") && !mode.equals("rules")) {
            throw new MealException("mode must be auto or rules");
        }
        if (request == null) {
            throw new MealException("Request body is required");
        }
        return recommendationService.recommend(sessionId, userId, request.message(), mode.equals("rules"));
    }
}
