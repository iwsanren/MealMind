package com.mealmind.service.feedback;

import com.mealmind.dto.feedback.FeedbackRequest;
import com.mealmind.entity.FeedbackRow;
import com.mealmind.exception.MealException;
import com.mealmind.mapper.FeedbackMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedbackServiceTest {

    // records every insert() call so a valid save() can be asserted too
    private static class RecordingFeedbackMapper implements FeedbackMapper {
        final List<FeedbackRow> inserted = new ArrayList<>();

        @Override
        public int insert(FeedbackRow row) {
            inserted.add(row);
            return 1;
        }
    }

    private final RecordingFeedbackMapper feedbackMapper = new RecordingFeedbackMapper();
    private final FeedbackService feedbackService = new FeedbackService(feedbackMapper);

    @Test
    void blankSessionIdIsRejected() {
        FeedbackRequest request = new FeedbackRequest(" ", 1L, "like", null, null);

        assertThatThrownBy(() -> feedbackService.save(1L, request))
                .isInstanceOf(MealException.class);
        assertThat(feedbackMapper.inserted).isEmpty();
    }

    @Test
    void blankActionIsRejected() {
        FeedbackRequest request = new FeedbackRequest("sess_abc", 1L, "", null, null);

        assertThatThrownBy(() -> feedbackService.save(1L, request))
                .isInstanceOf(MealException.class);
        assertThat(feedbackMapper.inserted).isEmpty();
    }

    @Test
    void validRequestIsInserted() {
        FeedbackRequest request = new FeedbackRequest("sess_abc", 5L, "dislike", 2, "too spicy");

        feedbackService.save(9L, request);

        assertThat(feedbackMapper.inserted).hasSize(1);
        FeedbackRow row = feedbackMapper.inserted.get(0);
        assertThat(row.getUserId()).isEqualTo(9L);
        assertThat(row.getSessionId()).isEqualTo("sess_abc");
        assertThat(row.getItemId()).isEqualTo(5L);
        assertThat(row.getAction()).isEqualTo("dislike");
        assertThat(row.getRating()).isEqualTo(2);
        assertThat(row.getReason()).isEqualTo("too spicy");
    }
}
