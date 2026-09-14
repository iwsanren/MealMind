package com.mealmind.service.slot;

import com.mealmind.mapper.SlotOptionMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
}
