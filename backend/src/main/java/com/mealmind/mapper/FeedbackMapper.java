package com.mealmind.mapper;

import com.mealmind.entity.FeedbackRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

// findBySessions (used by the future evaluation module, Part 2) intentionally
// left out - nothing in this codebase calls it yet.
@Mapper
public interface FeedbackMapper {

    int insert(FeedbackRow row);

    /** Newest first; rows without an item_id are skipped because they say nothing about a meal. */
    List<FeedbackRow> findRecentByUser(@Param("userId") Long userId, @Param("limit") int limit);
}
