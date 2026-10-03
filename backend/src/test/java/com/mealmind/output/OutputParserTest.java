package com.mealmind.output;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.assertj.core.api.Assertions.assertThat;

class OutputParserTest {

    // Same Jackson defaults Spring Boot uses, plus a real Bean Validation engine.
    private final OutputParser parser = new OutputParser(
            Jackson2ObjectMapperBuilder.json().build(),
            Validation.buildDefaultValidatorFactory().getValidator());

    private static final String VALID = """
            {"mealId": 12, "mealName": "grilled chicken bowl", "reason": "High protein.",
             "claims": [{"text": "high in protein", "source": "FROM_GUIDELINE", "guidelineRef": "DGA protein"}]}
            """;

    @Test
    void acceptsValidOutput() {
        ParseResult<RecommendationOutput> result = parser.parse(VALID, RecommendationOutput.class);

        assertThat(result.isValid()).isTrue();
        assertThat(result.value().mealName()).isEqualTo("grilled chicken bowl");
    }

    @Test
    void coercesNumericStringToNumber() {
        String json = VALID.replace("\"mealId\": 12", "\"mealId\": \"12\"");

        ParseResult<RecommendationOutput> result = parser.parse(json, RecommendationOutput.class);

        assertThat(result.isValid()).isTrue();
        assertThat(result.value().mealId()).isEqualTo(12L);
    }

    @Test
    void reportsEveryMissingRequiredField() {
        String json = """
                {"mealId": 12, "claims": [{"text": "x", "source": "INFERRED"}]}
                """;

        ParseResult<RecommendationOutput> result = parser.parse(json, RecommendationOutput.class);

        assertThat(result.errors()).extracting(FieldError::path)
                .containsExactlyInAnyOrder("mealName", "reason");
    }

    @Test
    void reportsTypeErrorWithFieldPath() {
        String json = VALID.replace("\"mealId\": 12", "\"mealId\": \"abc\"");

        ParseResult<RecommendationOutput> result = parser.parse(json, RecommendationOutput.class);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().get(0).path()).isEqualTo("mealId");
    }

    @Test
    void reportsBadEnumValueInsideNestedList() {
        String json = VALID.replace("FROM_GUIDELINE", "FROM_WIKIPEDIA");

        ParseResult<RecommendationOutput> result = parser.parse(json, RecommendationOutput.class);

        assertThat(result.errors().get(0).path()).isEqualTo("claims[0].source");
    }

    @Test
    void rejectsEmptyClaimsList() {
        String json = VALID.replaceAll("\"claims\": \\[.*\\]", "\"claims\": []");

        ParseResult<RecommendationOutput> result = parser.parse(json, RecommendationOutput.class);

        assertThat(result.errors()).extracting(FieldError::path).contains("claims");
    }

    @Test
    void reportsNonJsonTextAsRootError() {
        ParseResult<RecommendationOutput> result =
                parser.parse("Sure! I recommend the chicken bowl.", RecommendationOutput.class);

        assertThat(result.errors().get(0).path()).isEqualTo("$");
    }
}
