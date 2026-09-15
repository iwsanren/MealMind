package com.mealmind.controller.session;

import com.mealmind.constants.MealMindConstants;
import com.mealmind.dto.session.CreateSessionResponse;
import com.mealmind.enums.SourceMode;
import com.mealmind.service.session.SessionStateService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final SessionStateService sessionStateService;

    public SessionController(SessionStateService sessionStateService) {
        this.sessionStateService = sessionStateService;
    }

    @PostMapping
    public CreateSessionResponse create(
            @RequestHeader(value = MealMindConstants.USER_ID, defaultValue = "1") Long userId,
            @RequestParam(defaultValue = "PUBLIC") SourceMode sourceMode) {
        return new CreateSessionResponse(sessionStateService.create(userId, sourceMode).sessionId());
    }
}
