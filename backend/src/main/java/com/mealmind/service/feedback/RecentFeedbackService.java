package com.mealmind.service.feedback;

import com.mealmind.entity.FeedbackRow;
import com.mealmind.entity.MealItemRow;
import com.mealmind.exception.MealException;
import com.mealmind.mapper.FeedbackMapper;
import com.mealmind.mapper.MealMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read side of recommend_feedback for the agent: "what did this user recently
 * LIKE or DISLIKE?". Deliberately not "what did they eat" - the system never
 * records that, only reactions to recommendations.
 */
@Service
public class RecentFeedbackService {

    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 50;

    private final FeedbackMapper feedbackMapper;
    private final MealMapper mealMapper;

    public RecentFeedbackService(FeedbackMapper feedbackMapper, MealMapper mealMapper) {
        this.feedbackMapper = feedbackMapper;
        this.mealMapper = mealMapper;
    }

    /** One reaction, with the meal name resolved. action is normalized to upper case (the column is free text). */
    public record RecentFeedback(Long mealId, String mealName, String action, LocalDateTime createdAt) {
    }

    /**
     * Newest first. The limit applies to feedback rows BEFORE meals are resolved, so rows whose
     * meal was deleted (item_id is a soft reference) or is not visible to this user are dropped,
     * and the result can be shorter than the limit.
     */
    public List<RecentFeedback> findRecent(Long userId, Integer limit) {
        if (userId == null) {
            throw new MealException("userId is required");
        }
        int effectiveLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (effectiveLimit < 1 || effectiveLimit > MAX_LIMIT) {
            throw new MealException("limit must be between 1 and " + MAX_LIMIT);
        }

        List<FeedbackRow> rows = feedbackMapper.findRecentByUser(userId, effectiveLimit);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> itemIds = rows.stream().map(FeedbackRow::getItemId).distinct().toList();
        Map<Long, String> namesById = new HashMap<>();
        for (MealItemRow meal : mealMapper.findAccessibleByIds(itemIds, userId)) {
            namesById.put(meal.getId(), meal.getName());
        }
        return rows.stream()
                .filter(row -> namesById.containsKey(row.getItemId()))
                .map(row -> new RecentFeedback(row.getItemId(), namesById.get(row.getItemId()),
                        row.getAction().toUpperCase(Locale.ROOT), row.getCreatedAt()))
                .toList();
    }
}
