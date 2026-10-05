package com.mealmind.enums;

import com.mealmind.exception.MealException;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Closed vocabulary for meal_item.allergens and for the search filter
 * excludeAllergens. Free text is deliberately not allowed: "shellfish" and
 * "Shell fish" must never be two different things to a safety filter.
 *
 * The nine categories are the FDA "major food allergens" (milk, eggs, fish,
 * crustacean shellfish, tree nuts, peanuts, wheat, soybeans, sesame).
 * SHELLFISH is intentionally broader than the FDA term: the app treats it as
 * crustaceans AND molluscs, so excluding "shellfish" is fail-safe for users
 * who avoid clams or oysters as well as shrimp or crab.
 */
public enum Allergen {
    MILK("milk"),
    EGG("egg"),
    FISH("fish"),
    SHELLFISH("shellfish"),
    TREE_NUT("tree_nut"),
    PEANUT("peanut"),
    WHEAT("wheat"),
    SOY("soy"),
    SESAME("sesame");

    private final String token;

    Allergen(String token) {
        this.token = token;
    }

    /** Lower-case wire/database value, e.g. "tree_nut". */
    public String token() {
        return token;
    }

    /** All valid tokens, in declaration order. */
    public static List<String> tokens() {
        return Arrays.stream(values()).map(Allergen::token).toList();
    }

    /** Parses a token (case-insensitive, trimmed); anything outside the vocabulary is a MealException. */
    public static Allergen fromToken(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        for (Allergen allergen : values()) {
            if (allergen.token.equals(normalized)) {
                return allergen;
            }
        }
        throw new MealException("Unknown allergen '" + raw + "'; allowed values: " + tokens());
    }
}
