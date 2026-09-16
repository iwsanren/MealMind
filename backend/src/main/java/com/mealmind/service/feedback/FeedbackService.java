package com.mealmind.service.feedback;

import com.mealmind.dto.feedback.FeedbackRequest;
import com.mealmind.entity.FeedbackRow;
import com.mealmind.exception.MealException;
import com.mealmind.mapper.FeedbackMapper;
import org.springframework.stereotype.Service;

@Service
public class FeedbackService {

    private final FeedbackMapper feedbackMapper;

    public FeedbackService(FeedbackMapper feedbackMapper) {
        this.feedbackMapper = feedbackMapper;
    }

    /**
     * Only checks sessionId/action are present - it does not verify sessionId
     * refers to a real session_state row (matches the reference behavior).
     */
    public void save(Long userId, FeedbackRequest request) {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            throw new MealException("Feedback sessionId must not be blank");
        }
        if (request.action() == null || request.action().isBlank()) {
            throw new MealException("Feedback action must not be blank");
        }
        FeedbackRow row = new FeedbackRow();
        row.setUserId(userId);
        row.setSessionId(request.sessionId());
        row.setItemId(request.itemId());
        row.setAction(request.action());
        row.setRating(request.rating());
        row.setReason(request.reason());
        feedbackMapper.insert(row);
    }
}
