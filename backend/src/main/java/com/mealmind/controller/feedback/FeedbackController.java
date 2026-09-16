package com.mealmind.controller.feedback;

import com.mealmind.constants.MealMindConstants;
import com.mealmind.dto.feedback.FeedbackRequest;
import com.mealmind.service.feedback.FeedbackService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/diet/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping
    public void save(
            @RequestHeader(value = MealMindConstants.USER_ID, defaultValue = "1") Long userId,
            @RequestBody FeedbackRequest request) {
        feedbackService.save(userId, request);
    }
}
