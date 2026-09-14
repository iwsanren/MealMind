package com.mealmind.service.slot;

import com.mealmind.model.SlotBundle;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Merges the slots carried over from earlier turns of a conversation with the
 * slots extracted from the current turn. Called by the (future) Orchestrator
 * before running search/rank, so a user doesn't have to repeat every
 * preference in every message.
 */
@Service
public class SlotMergeService {

    /**
     * Per dimension: a non-empty current value replaces history; an empty
     * current value means "not mentioned this turn" and history is kept.
     */
    public SlotBundle merge(SlotBundle history, SlotBundle current) {
        SlotBundle safeHistory = history == null ? SlotBundle.empty() : history;
        SlotBundle safeCurrent = current == null ? SlotBundle.empty() : current;
        return new SlotBundle(
                choose(safeHistory.mealTime(), safeCurrent.mealTime()),
                choose(safeHistory.mood(), safeCurrent.mood()),
                choose(safeHistory.scene(), safeCurrent.scene()),
                choose(safeHistory.healthGoal(), safeCurrent.healthGoal()),
                choose(safeHistory.cuisine(), safeCurrent.cuisine()),
                choose(safeHistory.taste(), safeCurrent.taste()),
                choose(safeHistory.convenience(), safeCurrent.convenience())
        );
    }

    private List<String> choose(List<String> history, List<String> current) {
        return current == null || current.isEmpty() ? history : current;
    }
}
