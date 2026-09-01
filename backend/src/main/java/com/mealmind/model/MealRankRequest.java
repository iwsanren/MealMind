package com.mealmind.model;

import java.util.List;

/**
 * Layer2: Ranking. “Of the tags the user wants, how many does this dish satisfy?” (Layer1: Search)
 * candidates plus the state the ranking step consumes: the merged query slots
 * and the ids to exclude (previous picks).
 */
public record MealRankRequest(
        List<MealItem> candidates,
        SlotBundle slots,
        List<Long> excludeMealIds
) {
}
