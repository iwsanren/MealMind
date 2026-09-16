package com.mealmind.mapper;

import com.mealmind.entity.FeedbackRow;
import org.apache.ibatis.annotations.Mapper;

// findBySessions (used by the future evaluation module, Part 2) intentionally
// left out - nothing in this codebase calls it yet.
@Mapper
public interface FeedbackMapper {

    int insert(FeedbackRow row);
}
