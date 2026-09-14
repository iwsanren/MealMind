package com.mealmind.service.slot;

import com.mealmind.exception.MealException;
import com.mealmind.mapper.SlotOptionMapper;
import com.mealmind.model.SlotBundle;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SlotOptionService {

    /** The 7 meal dimensions, in the order every slot-related response should list them. */
    public static final List<String> SLOT_NAMES = List.of(
            "mealTime", "mood", "scene", "healthGoal", "cuisine", "taste", "convenience"
    );

    private final SlotOptionMapper slotOptionMapper;

    public SlotOptionService(SlotOptionMapper slotOptionMapper) {
        this.slotOptionMapper = slotOptionMapper;
    }

    /** One DB round-trip per dimension; small, enabled-only lists, so this stays cheap. */
    public Map<String, List<String>> findAllOptions() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String slotName : SLOT_NAMES) {
            result.put(slotName, slotOptionMapper.findEnabledValues(slotName));
        }
        return result;
    }

    /**
     * Reject any tag not present in the dictionary, dimension by dimension.
     * An empty dimension is not an error here (SlotBundle already normalizes
     * null -> empty list) - "the user didn't fill this in" is a valid state;
     * only a filled-in tag that isn't a known option is invalid.
     */
    public void validate(SlotBundle slots) {
        Map<String, List<String>> options = findAllOptions();
        validateSlot("mealTime", slots.mealTime(), options);
        validateSlot("mood", slots.mood(), options);
        validateSlot("scene", slots.scene(), options);
        validateSlot("healthGoal", slots.healthGoal(), options);
        validateSlot("cuisine", slots.cuisine(), options);
        validateSlot("taste", slots.taste(), options);
        validateSlot("convenience", slots.convenience(), options);
    }

    private void validateSlot(String slotName, List<String> values, Map<String, List<String>> options) {
        if (values == null || values.isEmpty()) {
            return;
        }
        Set<String> allowed = Set.copyOf(options.getOrDefault(slotName, List.of()));
        for (String value : values) {
            if (!allowed.contains(value)) {
                throw new MealException("Invalid slot tag: " + slotName + "=" + value);
            }
        }
    }
}
