package com.mealmind.controller.internal;

import com.mealmind.dto.internal.RecentFeedbackResponse;
import com.mealmind.service.feedback.RecentFeedbackService;
import com.mealmind.service.feedback.RecentFeedbackService.RecentFeedback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Internal API for ai-service: the user's most recent LIKE / DISLIKE reactions. */
@RestController
@RequestMapping("/internal/v1/users")
public class InternalFeedbackController {

    private final RecentFeedbackService recentFeedbackService;

    public InternalFeedbackController(RecentFeedbackService recentFeedbackService) {
        this.recentFeedbackService = recentFeedbackService;
    }

    // userId is always explicit (path), never the X-User-Id default of 1 used by the public API.
    @GetMapping("/{userId}/recent-feedback")
    public RecentFeedbackResponse recentFeedback(
            @PathVariable Long userId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        List<RecentFeedback> items = recentFeedbackService.findRecent(userId, limit);
        return new RecentFeedbackResponse(userId, items.size(), items);
    }
}
