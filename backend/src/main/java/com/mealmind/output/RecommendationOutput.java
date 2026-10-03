package com.mealmind.output;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * The shape the LLM must return for one meal recommendation. Jackson handles
 * type coercion ("12" -> 12) and rejects wrong types; Bean Validation
 * annotations handle "required" and reports every violated field at once.
 */
public record RecommendationOutput(
        @NotNull Long mealId,
        @NotBlank String mealName,
        @NotBlank String reason,
        @NotEmpty @Valid List<@NotNull @Valid Claim> claims
) {

    /** One factual claim in the reason, with where the model says it came from. */
    public record Claim(
            @NotBlank String text,
            @NotNull Source source,
            String guidelineRef
    ) {
    }

    /** Same four tags as prompts/meal_recommendation/v3.txt. */
    public enum Source {
        FROM_CANDIDATE,
        FROM_PREFERENCE,
        FROM_GUIDELINE,
        INFERRED
    }
}
