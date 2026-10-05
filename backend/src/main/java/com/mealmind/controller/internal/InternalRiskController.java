package com.mealmind.controller.internal;

import com.mealmind.dto.internal.RiskCheckRequest;
import com.mealmind.exception.MealException;
import com.mealmind.model.RiskGuardResult;
import com.mealmind.service.risk.RiskGuardService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal API for ai-service: run the existing RiskGuard over a piece of text. */
@RestController
@RequestMapping("/internal/v1/risk")
public class InternalRiskController {

    private final RiskGuardService riskGuardService;

    public InternalRiskController(RiskGuardService riskGuardService) {
        this.riskGuardService = riskGuardService;
    }

    @PostMapping("/check")
    public RiskGuardResult check(@RequestBody RiskCheckRequest request) {
        // RiskGuardService treats null as an empty string and passes it. For a safety check that is
        // fail-open, so the HTTP layer rejects missing or blank text instead of reporting "no risk".
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new MealException("text is required and must not be blank");
        }
        return riskGuardService.check(request.text());
    }
}
