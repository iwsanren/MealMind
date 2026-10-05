package com.mealmind.service.recommend;

import com.mealmind.model.MealItem;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the two hard constraints (a budget and allergens) out of the user's own words with plain rules.
 * Used by the rule-based recommender, and as an independent second check on the agent's answer.
 *
 * It errs on the side of excluding too much: when the message contains an avoidance cue ("allergic", "no", "without",
 * "avoid"...), EVERY allergen word in it is excluded, even if the user may have meant "I want shrimp, no peanuts".
 * A wrongly skipped dish costs a click; a wrongly shown allergen can hurt someone.
 */
@Service
public class HardConstraintExtractor {

    /** Allergen token -> words that signal it. Tokens are the Allergen enum's. */
    private static final Map<String, List<String>> WORDS = Map.of(
            "milk", List.of("milk", "dairy", "lactose", "cheese", "yogurt", "yoghurt", "butter", "cream"),
            "egg", List.of("egg", "eggs"),
            "fish", List.of("fish", "salmon", "tuna", "cod", "anchovy", "anchovies"),
            "shellfish", List.of("shellfish", "shrimp", "prawn", "prawns", "crab", "lobster", "clam", "clams",
                    "oyster", "oysters", "mussel", "mussels", "scallop", "scallops"),
            "tree_nut", List.of("tree nut", "tree nuts", "nut", "nuts", "almond", "almonds", "walnut", "walnuts",
                    "cashew", "cashews", "pecan", "pecans", "pistachio", "pistachios", "hazelnut", "hazelnuts"),
            "peanut", List.of("peanut", "peanuts", "nut", "nuts"),
            "wheat", List.of("wheat", "gluten"),
            "soy", List.of("soy", "soya", "soybean", "soybeans", "tofu"),
            "sesame", List.of("sesame", "tahini")
    );

    private static final Pattern AVOIDANCE_CUE = Pattern.compile(
            "allerg|intoleran|sensitiv|can'?t (?:eat|have)|cannot (?:eat|have)|can not (?:eat|have)|don'?t (?:eat|want)"
                    + "|do not (?:eat|want)|not eat|avoid|without|except|never|-free|\\bfree\\b|\\bno\\b");
    private static final Pattern ALLERGY_WORD = Pattern.compile("allerg|intoleran");

    // "under $15", "below 12", "at most 20 dollars", "$12", "15 bucks"; a number followed by a unit that is not money
    // ("under 30 minutes", "under 500 cal") is not a budget.
    private static final String NOT_A_UNIT = "(?!\\d)(?!\\.\\d)(?!\\s*(?:min|hour|hr|cal|kcal|kg|g\\b|gram|mg|%|oz|lb))";
    private static final Pattern BUDGET = Pattern.compile(
            "(?:under|below|less than|cheaper than|within|up to|at most|no more than|max(?:imum)?(?: of)?"
                    + "|budget(?: of| is)?|<=?)\\s*(?:usd\\s*|us\\s*)?\\$?\\s*(\\d+(?:\\.\\d{1,2})?)" + NOT_A_UNIT
                    + "|\\$\\s*(\\d+(?:\\.\\d{1,2})?)" + NOT_A_UNIT
                    + "|(\\d+(?:\\.\\d{1,2})?)\\s*(?:dollars?|bucks|usd)\\b");

    public record HardConstraints(BigDecimal maxPrice, Set<String> excludeAllergens, boolean unresolvedAllergy) {
        public boolean any() {
            return maxPrice != null || !excludeAllergens.isEmpty();
        }
    }

    public HardConstraints extract(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return new HardConstraints(budget(text), allergens(text), unresolvedAllergy(text));
    }

    private BigDecimal budget(String text) {
        BigDecimal lowest = null;
        Matcher m = BUDGET.matcher(text);
        while (m.find()) {
            String number = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            BigDecimal value = new BigDecimal(number);
            if (lowest == null || value.compareTo(lowest) < 0) {
                lowest = value;
            }
        }
        return lowest;
    }

    private Set<String> allergens(String text) {
        Set<String> found = new LinkedHashSet<>();
        if (!AVOIDANCE_CUE.matcher(text).find()) {
            return found;
        }
        WORDS.forEach((token, words) -> {
            for (String word : words) {
                if (Pattern.compile("(?<![a-z])" + Pattern.quote(word) + "(?![a-z])").matcher(text).find()) {
                    found.add(token);
                    break;
                }
            }
        });
        return found;
    }

    /** "I'm allergic to kiwi": an allergy is stated but nothing in the closed allergen vocabulary matches it. */
    private boolean unresolvedAllergy(String text) {
        return ALLERGY_WORD.matcher(text).find() && allergens(text).isEmpty();
    }

    /** True when the meal breaks a constraint; an unknown price or unknown allergens count as breaking it. */
    public boolean violates(MealItem meal, HardConstraints constraints) {
        var facts = meal.facts();
        if (constraints.maxPrice() != null) {
            if (facts == null || facts.price() == null || facts.price().compareTo(constraints.maxPrice()) > 0) {
                return true;
            }
        }
        if (!constraints.excludeAllergens().isEmpty()) {
            if (facts == null || facts.allergens() == null) {
                return true;
            }
            return facts.allergens().stream().anyMatch(a -> constraints.excludeAllergens().contains(a.toLowerCase(Locale.ROOT)));
        }
        return false;
    }
}
