package com.mealmind.service.meal;

import com.mealmind.dto.meal.MealRequest;
import com.mealmind.enums.SourceMode;
import com.mealmind.model.MealItem;
import com.mealmind.model.SlotBundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-MySQL test of the hard-constraint SQL in MealMapper.search: budget and
 * allergen filters must run in SQL and must fail closed on unknown (NULL)
 * values. Needs the same database as the other integration tests; every row is
 * rolled back at the end of each test.
 */
@SpringBootTest
@Transactional
class MealSearchSqlIntegrationTest {

    // A user id no real or seed data uses, so only this test's rows are visible to the PERSONAL search.
    private static final long USER = 987_654_321L;

    @Autowired
    private MealService mealService;

    @BeforeEach
    void seed() {
        create("cheap-no-allergens", "8.00", List.of());
        create("exactly-15", "15.00", List.of("milk"));
        create("pricey-shellfish", "22.00", List.of("shellfish", "wheat"));
        create("cheap-shellfish", "9.00", List.of("shellfish"));
        create("unknown-price", null, List.of());
        create("unknown-allergens", "7.00", null);
    }

    private void create(String name, String price, List<String> allergens) {
        mealService.createPersonalMeal(USER, new MealRequest(name, List.of("Lunch"), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(),
                price == null ? null : new BigDecimal(price), null, null, allergens));
    }

    private List<String> search(BigDecimal maxPrice, List<String> excludeAllergens) {
        return mealService.search(SourceMode.PERSONAL, USER, SlotBundle.empty(), maxPrice, excludeAllergens)
                .stream().map(MealItem::name).toList();
    }

    @Test
    void noConstraintsReturnsEverything() {
        assertThat(search(null, List.of())).hasSize(6);
    }

    @Test
    void budgetIsInclusiveAndUnknownPriceFailsClosed() {
        assertThat(search(new BigDecimal("15.00"), List.of()))
                .containsExactlyInAnyOrder("cheap-no-allergens", "exactly-15", "cheap-shellfish", "unknown-allergens")
                .doesNotContain("pricey-shellfish", "unknown-price");
    }

    @Test
    void allergenFilterExcludesMatchesAndUnknownAllergens() {
        assertThat(search(null, List.of("shellfish")))
                .containsExactlyInAnyOrder("cheap-no-allergens", "exactly-15", "unknown-price")
                .doesNotContain("pricey-shellfish", "cheap-shellfish", "unknown-allergens");
    }

    @Test
    void constraintsCombine() {
        assertThat(search(new BigDecimal("15.00"), List.of("shellfish")))
                .containsExactlyInAnyOrder("cheap-no-allergens", "exactly-15");
    }

    @Test
    void emptyAllergenListMeansKnownSafeNotUnknown() {
        assertThat(search(null, List.of("milk", "wheat")))
                .contains("cheap-no-allergens", "unknown-price")
                .doesNotContain("exactly-15", "pricey-shellfish");
    }
}
