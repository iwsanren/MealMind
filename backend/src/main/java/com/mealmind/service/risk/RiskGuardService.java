package com.mealmind.service.risk;

import com.mealmind.model.RiskGuardResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Last-line compliance check before a reply goes out: scans text for
 * high-risk health/medical phrasing and, if any is found, tells the caller
 * to swap in a conservative fixed message instead of sending the draft as-is.
 */
@Service
public class RiskGuardService {

    private static final String[] MEDICAL_KEYWORDS = {
            "cure", "cures", "treat", "treats", "treatment", "diagnose", "diagnosis",
            "medication", "prescription", "prescribe"
    };

    private static final String[] EXTREME_DIET_KEYWORDS = {
            "starve", "starving", "skip all meals", "skip every meal",
            "only drink water", "water fast", "extreme diet", "zero calorie"
    };

    private static final String[] ABSOLUTE_CLAIM_KEYWORDS = {
            "guaranteed", "guarantee", "healthiest ever", "100% effective", "no side effects"
    };

    private static final String[] SPECIAL_POPULATION_KEYWORDS = {
            "pregnant", "pregnancy", "diabetes", "diabetic", "hypertension",
            "high blood pressure", "minor", "child", "children", "infant"
    };

    private static final String CONSERVATIVE_MESSAGE =
            "This touches on a health or medical risk, and I can't stand in for a doctor's "
                    + "diagnosis or treatment advice. Day to day, aim for meals that are light, "
                    + "balanced, and not oversized; if symptoms are significant, or a chronic "
                    + "condition or pregnancy is involved, please consult a doctor or a registered "
                    + "dietitian.";

    /**
     * Scans the given text (e.g. the user's message and/or a draft reply,
     * concatenated by the caller) for high-risk phrasing across 4 rule
     * categories. Any match blocks; no match passes.
     *
     * TODO (Part 2 / Orchestrator wiring): also block whenever the intent
     * layer has already classified this turn as HEALTH_RISK. That check
     * needs the Intent enum from Part 2, which doesn't exist yet.
     */
    public RiskGuardResult check(String text) {
        String safeText = text == null ? "" : text.toLowerCase();
        List<String> reasons = new ArrayList<>();

        if (containsAny(safeText, MEDICAL_KEYWORDS)) {
            reasons.add("Mentions medical diagnosis, treatment, or prescription claims");
        }
        if (containsAny(safeText, EXTREME_DIET_KEYWORDS)) {
            reasons.add("Mentions extreme dieting or fasting behavior");
        }
        if (containsAny(safeText, ABSOLUTE_CLAIM_KEYWORDS)) {
            reasons.add("Mentions an absolute health or weight-loss guarantee");
        }
        if (containsAny(safeText, SPECIAL_POPULATION_KEYWORDS)) {
            reasons.add("Mentions a special population or chronic condition");
        }

        if (reasons.isEmpty()) {
            return RiskGuardResult.pass();
        }
        return RiskGuardResult.block(reasons, CONSERVATIVE_MESSAGE);
    }

    private boolean containsAny(String text, String[] keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
