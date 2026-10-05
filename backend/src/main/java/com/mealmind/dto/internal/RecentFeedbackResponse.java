package com.mealmind.dto.internal;

import com.mealmind.service.feedback.RecentFeedbackService.RecentFeedback;

import java.util.List;

public record RecentFeedbackResponse(Long userId, int count, List<RecentFeedback> items) {
}
