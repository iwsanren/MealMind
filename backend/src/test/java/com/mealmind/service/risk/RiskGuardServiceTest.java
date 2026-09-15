package com.mealmind.service.risk;

import com.mealmind.model.RiskGuardResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RiskGuardServiceTest {

    private final RiskGuardService riskGuardService = new RiskGuardService();

    @Test
    void blocksMedicalDiagnosisOrTreatmentClaims() {
        RiskGuardResult result = riskGuardService.check("Can this diet treat my condition without medication?");

        assertThat(result.blocked()).isTrue();
        assertThat(result.reasons()).contains("Mentions medical diagnosis, treatment, or prescription claims");
        assertThat(result.conservativeMessage()).isNotBlank();
    }

    @Test
    void blocksExtremeDietingBehavior() {
        RiskGuardResult result = riskGuardService.check("Just tell me to starve for two days, or only drink water.");

        assertThat(result.blocked()).isTrue();
        assertThat(result.reasons()).contains("Mentions extreme dieting or fasting behavior");
    }

    @Test
    void blocksAbsoluteHealthOrWeightLossGuarantees() {
        RiskGuardResult result = riskGuardService.check("This plan is guaranteed to work, it's the healthiest ever.");

        assertThat(result.blocked()).isTrue();
        assertThat(result.reasons()).contains("Mentions an absolute health or weight-loss guarantee");
    }

    @Test
    void blocksSpecialPopulationOrChronicConditionMentions() {
        RiskGuardResult result = riskGuardService.check("I'm pregnant and also have diabetes, what should I eat?");

        assertThat(result.blocked()).isTrue();
        assertThat(result.reasons()).contains("Mentions a special population or chronic condition");
    }

    @Test
    void collectsAllMatchingReasonsWhenMultipleRulesHit() {
        RiskGuardResult result = riskGuardService.check(
                "This will cure your condition, guaranteed, even if you're pregnant and starve yourself.");

        assertThat(result.blocked()).isTrue();
        assertThat(result.reasons()).containsExactlyInAnyOrder(
                "Mentions medical diagnosis, treatment, or prescription claims",
                "Mentions extreme dieting or fasting behavior",
                "Mentions an absolute health or weight-loss guarantee",
                "Mentions a special population or chronic condition"
        );
    }

    @Test
    void passesOrdinaryTextWithNoRiskKeywords() {
        RiskGuardResult result = riskGuardService.check("I'd like a light lunch that's quick to prepare.");

        assertThat(result.blocked()).isFalse();
        assertThat(result.reasons()).isEmpty();
        assertThat(result.conservativeMessage()).isNull();
    }

    @Test
    void nullTextIsTreatedAsEmptyNotAnError() {
        RiskGuardResult result = riskGuardService.check(null);

        assertThat(result.blocked()).isFalse();
    }
}
