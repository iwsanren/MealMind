package com.mealmind.service.recommend;

import com.mealmind.model.SlotBundle;
import com.mealmind.service.slot.SlotOptionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Finds slot tags in the user's message by matching the enabled slot vocabulary word-for-word (the same idea as the
 * chat page's old simulated reply, now on the server). Pure rules, no LLM. It knows nothing about budgets or
 * allergens; those are HardConstraintExtractor's job.
 */
@Service
public class RuleSlotExtractor {

    private final SlotOptionService slotOptionService;

    public RuleSlotExtractor(SlotOptionService slotOptionService) {
        this.slotOptionService = slotOptionService;
    }

    public SlotBundle extract(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        Map<String, List<String>> options = slotOptionService.findAllOptions();
        return new SlotBundle(
                matches(text, options.get("mealTime")),
                matches(text, options.get("mood")),
                matches(text, options.get("scene")),
                matches(text, options.get("healthGoal")),
                matches(text, options.get("cuisine")),
                matches(text, options.get("taste")),
                matches(text, options.get("convenience")));
    }

    /** A tag matches only as whole words, so "Light" is not found inside "highlight". */
    private List<String> matches(String text, List<String> tags) {
        List<String> found = new ArrayList<>();
        for (String tag : tags == null ? List.<String>of() : tags) {
            String lower = tag.toLowerCase(Locale.ROOT);
            if (Pattern.compile("(?<![a-z])" + Pattern.quote(lower) + "(?![a-z])").matcher(text).find()) {
                found.add(tag);
            }
        }
        return found;
    }
}
