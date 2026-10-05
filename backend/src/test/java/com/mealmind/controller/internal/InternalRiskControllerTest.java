package com.mealmind.controller.internal;

import com.mealmind.service.risk.RiskGuardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of POST /internal/v1/risk/check, using the REAL RiskGuardService (it is pure logic). */
@WebMvcTest(InternalRiskController.class)
@Import(RiskGuardService.class)
class InternalRiskControllerTest {

    @Autowired
    MockMvc mockMvc;

    private ResultActions check(String json) throws Exception {
        return mockMvc.perform(post("/internal/v1/risk/check").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void harmlessTextPasses() throws Exception {
        check("{\"text\":\"Grilled chicken bowl with rice and vegetables\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(false))
                .andExpect(jsonPath("$.reasons").isEmpty());
    }

    @Test
    void medicalClaimIsBlockedWithReasonsAndConservativeMessage() throws Exception {
        check("{\"text\":\"This meal will cure your diabetes\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(true))
                .andExpect(jsonPath("$.reasons").isNotEmpty())
                .andExpect(jsonPath("$.conservativeMessage").isNotEmpty());
    }

    @Test
    void missingOrBlankTextIsA400NotASilentPass() throws Exception {
        check("{}").andExpect(status().isBadRequest());
        check("{\"text\":\"   \"}").andExpect(status().isBadRequest());
    }

    /**
     * KNOWN FALSE POSITIVE, pinned on purpose (found by reading the code in step 0, confirmed here):
     * RiskGuardService matches substrings, and "treat" is a medical keyword, so a harmless user
     * mood like "Want to Treat Myself" (a real slot option) is blocked. If RiskGuard is fixed,
     * flip this assertion.
     */
    @Test
    void knownFalsePositive_treatMyselfIsBlocked() throws Exception {
        check("{\"text\":\"I want to treat myself tonight\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(true));
    }
}
