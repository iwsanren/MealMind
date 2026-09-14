package com.mealmind.service.clarify;

import com.mealmind.model.SlotBundle;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether the slots gathered so far are enough to recommend, or the
 * user should be asked a follow-up question first. This is a fixed Java rule,
 * not an LLM call, so the go/no-go decision stays deterministic; an LLM-backed
 * clarify step (if any) only phrases the question, it doesn't decide whether
 * to ask one.
 */
@Service
public class ClarifyRuleService {

    /** Enough to recommend when there is nothing left to ask about. */
    public boolean hasEnoughSlots(SlotBundle slots) {
        return missingSlots(slots).isEmpty();
    }

    /**
     * Required: mealTime. Also required: healthGoal, but only when the user
     * hasn't expressed any other strong preference (cuisine/taste/scene/
     * convenience) - in that case a health goal isn't worth interrupting for.
     */
    public List<String> missingSlots(SlotBundle slots) {
        SlotBundle safeSlots = slots == null ? SlotBundle.empty() : slots;
        List<String> missing = new ArrayList<>();
        if (safeSlots.mealTime().isEmpty()) {
            missing.add("mealTime");
        }
        if (safeSlots.healthGoal().isEmpty() && !hasStrongFoodPreference(safeSlots)) {
            missing.add("healthGoal");
        }
        return missing;
    }

    /** Any one of these filled in is enough to skip the healthGoal follow-up. */
    private boolean hasStrongFoodPreference(SlotBundle slots) {
        return !slots.cuisine().isEmpty()
                || !slots.taste().isEmpty()
                || !slots.scene().isEmpty()
                || !slots.convenience().isEmpty();
    }

    /** Template question used when there's nothing (or nothing usable) from the LLM clarifier. */
    public String fallbackQuestion(List<String> missingSlots) {
        if (missingSlots == null || missingSlots.isEmpty()) {
            return "Would you rather go by flavor, or by a goal like light or filling?";
        }
        if (missingSlots.contains("mealTime")) {
            return "Is this for breakfast, lunch, or dinner?";
        }
        if (missingSlots.contains("healthGoal")) {
            return "Would you rather go light, filling, or just pick by flavor?";
        }
        return "Just to confirm - what matters most this time: flavor, a health goal, or convenience?";
    }
}
