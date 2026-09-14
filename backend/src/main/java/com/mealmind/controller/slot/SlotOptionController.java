package com.mealmind.controller.slot;

import com.mealmind.service.slot.SlotOptionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/slot-options")
public class SlotOptionController {

    private final SlotOptionService slotOptionService;

    public SlotOptionController(SlotOptionService slotOptionService) {
        this.slotOptionService = slotOptionService;
    }

    @GetMapping
    public Map<String, List<String>> findAll() {
        return slotOptionService.findAllOptions();
    }
}
