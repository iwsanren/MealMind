package com.mealmind.service.feedback;

import com.mealmind.entity.FeedbackRow;
import com.mealmind.entity.MealItemRow;
import com.mealmind.exception.MealException;
import com.mealmind.mapper.FeedbackMapper;
import com.mealmind.mapper.MealMapper;
import com.mealmind.service.feedback.RecentFeedbackService.RecentFeedback;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecentFeedbackServiceTest {

    private final FeedbackMapper feedbackMapper = mock(FeedbackMapper.class);
    private final MealMapper mealMapper = mock(MealMapper.class);
    private final RecentFeedbackService service = new RecentFeedbackService(feedbackMapper, mealMapper);

    private static FeedbackRow feedback(Long itemId, String action, LocalDateTime at) {
        FeedbackRow row = new FeedbackRow();
        row.setItemId(itemId);
        row.setAction(action);
        row.setCreatedAt(at);
        return row;
    }

    private static MealItemRow meal(long id, String name) {
        MealItemRow row = new MealItemRow();
        row.setId(id);
        row.setName(name);
        return row;
    }

    @Test
    void resolvesMealNamesNormalizesActionAndDropsMissingMeals() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 5, 12, 0);
        when(feedbackMapper.findRecentByUser(7L, 10)).thenReturn(List.of(
                feedback(2L, "dislike", t), feedback(999L, "LIKE", t.minusHours(1)), feedback(1L, "Like", t.minusHours(2))));
        when(mealMapper.findAccessibleByIds(any(), any())).thenReturn(List.of(meal(1, "Salad"), meal(2, "Burger")));

        List<RecentFeedback> result = service.findRecent(7L, null);

        // 999 was deleted (soft reference), so it disappears; order stays newest first
        assertThat(result).extracting(RecentFeedback::mealName).containsExactly("Burger", "Salad");
        assertThat(result).extracting(RecentFeedback::action).containsExactly("DISLIKE", "LIKE");
    }

    @Test
    void noFeedbackSkipsTheMealLookup() {
        when(feedbackMapper.findRecentByUser(7L, 10)).thenReturn(List.of());

        assertThat(service.findRecent(7L, null)).isEmpty();
        verify(mealMapper, never()).findAccessibleByIds(any(), any());
    }

    @Test
    void validatesUserIdAndLimit() {
        assertThatThrownBy(() -> service.findRecent(null, 5)).isInstanceOf(MealException.class);
        assertThatThrownBy(() -> service.findRecent(7L, 0)).isInstanceOf(MealException.class);
        assertThatThrownBy(() -> service.findRecent(7L, 51)).isInstanceOf(MealException.class);
    }
}
