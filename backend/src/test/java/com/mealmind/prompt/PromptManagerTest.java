package com.mealmind.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptManagerTest {

    private final PromptManager promptManager = newPromptManager();

    @Test
    void rendersCurrentVersionAndReportsWhichVersionWasUsed() {
        RenderedPrompt result = promptManager.render("meal_recommendation", Map.of(
                "preferences", "vegetarian",
                "constraints", "no peanuts",
                "candidates", "[grilled tofu bowl]"
        ));

        // prompts-config.yaml says meal_recommendation is currently v2
        assertThat(result.version()).isEqualTo("v2");
        assertThat(result.text()).contains("vegetarian", "no peanuts", "[grilled tofu bowl]");
        // v2 added source attribution tagging — this line proves v2 loaded, not v1
        assertThat(result.text()).contains("[INFERRED]");
    }

    @Test
    void canLoadAnOlderVersionDirectlyForRollbackOrComparison() {
        String v1Template = promptManager.load("meal_recommendation", "v1");

        assertThat(v1Template).doesNotContain("[INFERRED]");
    }

    @Test
    void throwsWhenScenarioHasNoConfiguredVersion() {
        assertThatThrownBy(() -> promptManager.currentVersion("unknown_scenario"))
                .isInstanceOf(PromptNotFoundException.class);
    }

    private static PromptManager newPromptManager() {
        PromptProperties properties = new PromptProperties();
        properties.setVersions(Map.of(
                "meal_recommendation", "v2",
                "meal_review", "v1"
        ));
        return new PromptManager(properties);
    }
}
