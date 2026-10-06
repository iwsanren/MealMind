package com.mealmind.service.recommend;

import com.mealmind.enums.SourceMode;
import com.mealmind.model.MealFacts;
import com.mealmind.model.MealItem;
import com.mealmind.model.SlotBundle;
import com.mealmind.service.recommend.HardConstraintExtractor.HardConstraints;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HardConstraintExtractorTest {

    private final HardConstraintExtractor extractor = new HardConstraintExtractor();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "dinner under $15, please|15",
            "Under $15.|15",
            "something below 12|12",
            "at most 20 dollars|20",
            "I only have $8.50|8.50",
            "15 bucks max|15",
            "budget is 9|9",
            "less than $10 and under $7|7",
            // euros: symbol before or after the number, a word, a cue word, and a decimal comma
            "dinner under €15, please|15",
            "Under € 15.|15",
            "I only have €8.50|8.50",
            "I only have 8,50 €|8.50",
            "under 12,50|12.50",
            "something for 8€|8",
            "15 euros max|15",
            "at most 20 euro|20",
            "10 eur|10",
            "budget of EUR 9|9",
            "less than €10 and under 7 euros|7",
    })
    void readsABudget(String message, String expected) {
        assertThat(extractor.extract(message).maxPrice()).isEqualByComparingTo(new BigDecimal(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ready in under 30 minutes", "under 500 calories", "over 30g protein", "something tasty",
            "under 20 min please", "less than 600 kcal",
            // a thousands separator is not a decimal comma
            "under 1,200 calories", "under 1,200"})
    void aNumberWithoutMoneyIsNotABudget(String message) {
        assertThat(extractor.extract(message).maxPrice()).isNull();
    }

    @Test
    void allergensNeedAnAvoidanceCue() {
        assertThat(extractor.extract("I'm allergic to shrimp").excludeAllergens()).containsExactly("shellfish");
        assertThat(extractor.extract("no peanuts, please").excludeAllergens()).containsExactly("peanut");
        assertThat(extractor.extract("dairy free dinner").excludeAllergens()).containsExactly("milk");
        // wanting a food is not avoiding it
        assertThat(extractor.extract("I want shrimp pasta tonight").excludeAllergens()).isEmpty();
    }

    @Test
    void aGenericNutAllergyExcludesBothNutTypes() {
        assertThat(extractor.extract("nut allergy").excludeAllergens()).containsExactlyInAnyOrder("tree_nut", "peanut");
    }

    @Test
    void whenAnAvoidanceCueIsPresentEveryAllergenWordIsExcluded() {
        // fail-closed on purpose: "I want shrimp, no peanuts" also excludes shellfish
        assertThat(extractor.extract("I want shrimp, no peanuts").excludeAllergens())
                .containsExactlyInAnyOrder("shellfish", "peanut");
    }

    @Test
    void wordsInsideOtherWordsDoNotCount() {
        assertThat(extractor.extract("no nutmeg, no eggplant").excludeAllergens()).isEmpty();
        assertThat(extractor.extract("avoid creamy soups").excludeAllergens()).isEmpty();
    }

    @Test
    void anAllergyOutsideTheVocabularyIsFlaggedAsUnresolved() {
        HardConstraints kiwi = extractor.extract("I'm allergic to kiwi");
        assertThat(kiwi.unresolvedAllergy()).isTrue();
        assertThat(kiwi.excludeAllergens()).isEmpty();
        assertThat(extractor.extract("I'm allergic to shrimp").unresolvedAllergy()).isFalse();
        assertThat(extractor.extract("hello").unresolvedAllergy()).isFalse();
    }

    @Test
    void nothingStatedMeansNoConstraints() {
        HardConstraints none = extractor.extract("something light for lunch");
        assertThat(none.any()).isFalse();
        assertThat(extractor.extract(null).any()).isFalse();
    }

    private static MealItem meal(String price, List<String> allergens) {
        return new MealItem(1L, SourceMode.PUBLIC, null, "m", SlotBundle.empty(),
                new MealFacts(price == null ? null : new BigDecimal(price), null, null, allergens), 0d);
    }

    @Test
    void violatesIsFailClosedOnUnknownFacts() {
        HardConstraints budget = new HardConstraints(new BigDecimal("15"), Set.of(), false);
        assertThat(extractor.violates(meal("15.00", List.of()), budget)).isFalse();   // exactly at the budget is fine
        assertThat(extractor.violates(meal("15.01", List.of()), budget)).isTrue();
        assertThat(extractor.violates(meal(null, List.of()), budget)).isTrue();       // unknown price

        HardConstraints shellfish = new HardConstraints(null, Set.of("shellfish"), false);
        assertThat(extractor.violates(meal("1", List.of("milk")), shellfish)).isFalse();
        assertThat(extractor.violates(meal("1", List.of("milk", "shellfish")), shellfish)).isTrue();
        assertThat(extractor.violates(meal("1", null), shellfish)).isTrue();          // unknown allergens
        assertThat(extractor.violates(meal(null, null), new HardConstraints(null, Set.of(), false))).isFalse();
    }
}
