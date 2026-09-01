package com.mealmind.service.meal;

import com.mealmind.model.MealItem;
import com.mealmind.model.MealRankRequest;
import com.mealmind.model.SlotBundle;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Layer2: Ranking. “Of the tags the user wants, how many does this dish satisfy?” (Layer1: Search)
 *
 * search() asks "is there ANY tag overlap?" and decides candidate-set membership.
 * rank() asks "how MANY of the user's asked-for tags are actually satisfied?"
 * and orders candidates by that degree of satisfaction.
 */
@Service
public class MealRankService {

    /** Fixed divisor: the score is averaged over all seven slot dimensions. */
    private static final double DIMENSION_COUNT = 7.0;
    /** Cap on how many ranked candidates flow downstream. */
    private static final int TOP_N = 10;

    // TODO (later step): called by the Orchestrator recommendation flow after search.
    public List<MealItem> rank(MealRankRequest request) {
        // O(1) exclusion lookup for previous picks
        Set<Long> excludeIds = new HashSet<>(
                request.excludeMealIds() == null ? List.of() : request.excludeMealIds());

        return request.candidates().stream()
                .filter(item -> item != null && !excludeIds.contains(item.id()))
                .map(item -> item.withMatchScore(slotScore(item.slots(), request.slots())))
                .sorted(Comparator.comparingDouble(MealItem::matchScore).reversed())
                .limit(TOP_N)
                .toList();
    }

    /** Mean overlap ratio across the seven dimensions, clamped to [0, 1]. */
    private double slotScore(SlotBundle item, SlotBundle query) {
        SlotBundle q = query == null ? SlotBundle.empty() : query;
        double total = overlap(item.mealTime(),    q.mealTime())
                + overlap(item.mood(),        q.mood())
                + overlap(item.scene(),       q.scene())
                + overlap(item.healthGoal(),  q.healthGoal())
                + overlap(item.cuisine(),     q.cuisine())
                + overlap(item.taste(),       q.taste())
                + overlap(item.convenience(), q.convenience());
        // Each overlap() is already in [0,1] and we divide by 7, so this is
        // structurally in range; clamp() is a defensive guard.
        return clamp(total / DIMENSION_COUNT);
    }

    /**
     * Fraction of the query's tags on this dimension that the meal also carries.
     * An empty query dimension contributes 0 (the user did not ask for it).
     * Example: query [High protein, Light], meal [High protein] -> 1/2 = 0.5
     */
    private double overlap(List<String> itemValues, List<String> queryValues) {
        if (queryValues == null || queryValues.isEmpty()) {
            return 0d;
        }
        Set<String> itemSet = Set.copyOf(itemValues == null ? List.of() : itemValues);
        long hits = queryValues.stream().filter(itemSet::contains).count();
        return hits * 1.0 / queryValues.size();
    }

    private double clamp(double score) {
        return Math.max(0d, Math.min(1d, score));
    }
}