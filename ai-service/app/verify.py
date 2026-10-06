"""Deterministic checks of a drafted recommendation against the candidate data the agent actually retrieved.

Pure functions, no I/O. What it can check is bounded by what the data contains: price, protein, calories,
allergens and tags are checkable; adjectives like "light" or "healthy" are not. Unknown (null) facts are
never treated as passing: for a claim they are reported as UNVERIFIABLE, for a hard constraint they fail.
"""

import re
from dataclasses import dataclass, field
from enum import Enum

from app.schemas import ClaimField, ClaimSource, Constraints, Meal, Recommendation

# "High in protein" per FDA 21 CFR 101.54(b): >= 20% of the Daily Value per serving; the protein Daily Value is 50 g
# (https://www.fda.gov/food/nutrition-facts-label/daily-value-nutrition-and-supplement-facts-labels), so 20% x 50 g = 10 g.
# Simplifications (see notes/agent/data-model.md): no PDCAAS correction, per serving rather than per RACC.
HIGH_PROTEIN_G_PER_SERVING = 10.0

_TOLERANCE = 0.05  # numeric claims may differ by rounding noise only


class Severity(str, Enum):
    FAIL = "FAIL"                  # the draft is wrong or violates a hard constraint
    UNVERIFIABLE = "UNVERIFIABLE"  # the needed fact is unknown, so the claim cannot be confirmed


@dataclass(frozen=True)
class Issue:
    code: str
    severity: Severity
    message: str
    claim_index: int | None = None  # None = about the whole recommendation


@dataclass
class VerifyReport:
    issues: list[Issue] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        """True when there is no FAIL. UNVERIFIABLE claims are warnings, not failures."""
        return not any(i.severity is Severity.FAIL for i in self.issues)

    def to_dict(self) -> dict:
        return {
            "ok": self.ok,
            "issues": [
                {"code": i.code, "severity": i.severity.value, "message": i.message, "claim_index": i.claim_index}
                for i in self.issues
            ],
        }


def check_hard_constraints(meal: Meal, constraints: Constraints) -> list[Issue]:
    """The meal itself must satisfy the user's budget and allergen constraints; unknown never satisfies."""
    issues: list[Issue] = []
    if constraints.max_price is not None:
        if meal.price is None:
            issues.append(Issue("PRICE_UNKNOWN", Severity.FAIL,
                                f"'{meal.name}' has no price, so it cannot be shown to fit the {constraints.max_price:g} budget"))
        elif meal.price > constraints.max_price:
            issues.append(Issue("OVER_BUDGET", Severity.FAIL,
                                f"'{meal.name}' costs {meal.price:g}, over the {constraints.max_price:g} budget"))
    if constraints.exclude_allergens:
        if meal.allergens is None:
            issues.append(Issue("ALLERGENS_UNKNOWN", Severity.FAIL,
                                f"'{meal.name}' has unknown allergen information; it cannot be treated as safe"))
        else:
            hit = sorted(set(a.lower() for a in meal.allergens) & set(a.lower() for a in constraints.exclude_allergens))
            if hit:
                issues.append(Issue("CONTAINS_ALLERGEN", Severity.FAIL,
                                    f"'{meal.name}' contains {', '.join(hit)}, which the user must avoid"))
    return issues


def _allergen_value(claim) -> str | None:
    return claim.allergen.value if claim.allergen is not None else None


def _check_candidate_claim(index: int, claim, meal: Meal) -> list[Issue]:
    f = claim.field

    if f is ClaimField.OTHER:
        return [Issue("UNCHECKABLE_CANDIDATE_CLAIM", Severity.FAIL,
                      "FROM_CANDIDATE claims must name a checkable field (PRICE, PROTEIN_G, ...); "
                      "use INFERRED for anything else", index)]

    if f is ClaimField.TAG:
        if not claim.tag or claim.tag.strip().lower() not in meal.all_tags():
            return [Issue("TAG_NOT_ON_MEAL", Severity.FAIL, f"'{meal.name}' does not carry the tag '{claim.tag}'", index)]
        return []

    if f is ClaimField.ALLERGEN_FREE:
        allergen = _allergen_value(claim)
        if not allergen:
            return [Issue("MISSING_VALUE", Severity.FAIL, "ALLERGEN_FREE needs the allergen field", index)]
        if meal.allergens is None:
            return [Issue("ALLERGENS_UNKNOWN", Severity.UNVERIFIABLE,
                          f"allergen information for '{meal.name}' is unknown, so 'free of {allergen}' cannot be confirmed", index)]
        if allergen in {a.lower() for a in meal.allergens}:
            return [Issue("CONTAINS_ALLERGEN", Severity.FAIL, f"'{meal.name}' contains {allergen}", index)]
        return []

    if f is ClaimField.HIGH_PROTEIN:
        if meal.protein_g is None:
            return [Issue("PROTEIN_UNKNOWN", Severity.UNVERIFIABLE, f"protein for '{meal.name}' is unknown", index)]
        if meal.protein_g < HIGH_PROTEIN_G_PER_SERVING:
            return [Issue("NOT_HIGH_PROTEIN", Severity.FAIL,
                          f"'{meal.name}' has {meal.protein_g:g} g protein, below the {HIGH_PROTEIN_G_PER_SERVING:g} g "
                          f"threshold for 'high protein'", index)]
        return []

    # Numeric claims: PRICE, WITHIN_BUDGET, PROTEIN_G, CALORIES
    asserted = claim.number
    if asserted is None:
        return [Issue("MISSING_VALUE", Severity.FAIL, f"{f.value} needs the number field", index)]
    actual = {ClaimField.PRICE: meal.price, ClaimField.WITHIN_BUDGET: meal.price,
              ClaimField.PROTEIN_G: meal.protein_g, ClaimField.CALORIES: meal.calories}[f]
    if actual is None:
        return [Issue(f"{f.value}_UNKNOWN", Severity.UNVERIFIABLE, f"{f.value.lower()} for '{meal.name}' is unknown", index)]
    if f is ClaimField.WITHIN_BUDGET:
        if actual > asserted + 1e-9:
            return [Issue("OVER_BUDGET", Severity.FAIL, f"'{meal.name}' costs {actual:g}, over the {asserted:g} budget", index)]
        return []
    if abs(actual - asserted) > _TOLERANCE:
        return [Issue(f"{f.value}_MISMATCH", Severity.FAIL,
                      f"claimed {f.value.lower()} {asserted:g} but '{meal.name}' has {actual:g}", index)]
    return []


# Numbers with a unit inside the free-text reason. The structured claims are the main check, but a model can state a
# fact in "reason" and leave it out of "claims"; any number it writes must at least match the data it was given.
# A price has a currency marker before ("€12.50", "$12.50") or after ("12,50 €", "12 euros"), and may use a decimal comma.
# The check compares numbers only, so "$" stays accepted: a model that writes the wrong symbol is still held to the data.
_PRICE_IN_TEXT = re.compile(
    r"[€$]\s?(\d+(?:[.,]\d{1,2})?)"
    r"|(\d+(?:[.,]\d{1,2})?)\s?(?:€|euros?\b|eur\b)",
    re.IGNORECASE)
_GRAMS_IN_TEXT = re.compile(r"(\d+(?:\.\d+)?)\s?-?\s?(?:g|grams?)\b", re.IGNORECASE)
_KCAL_IN_TEXT = re.compile(r"(\d+(?:\.\d+)?)\s?(?:kcal|calories|cal)\b", re.IGNORECASE)


def check_reason_numbers(reason: str, meal: Meal, constraints: Constraints) -> list[Issue]:
    """Every price, gram or calorie figure written in the reason must equal a known fact (the meal's, or the budget)."""
    supported = {
        "price": [v for v in (meal.price, constraints.max_price) if v is not None],
        "grams": [v for v in (meal.protein_g,) if v is not None],
        "kcal": [v for v in (meal.calories,) if v is not None],
    }
    issues: list[Issue] = []
    for kind, pattern in (("price", _PRICE_IN_TEXT), ("grams", _GRAMS_IN_TEXT), ("kcal", _KCAL_IN_TEXT)):
        for match in pattern.finditer(reason):
            # The price pattern has two alternatives (marker first / number first), so take whichever group matched.
            number = float(next(g for g in match.groups() if g).replace(",", "."))
            if not any(abs(number - known) <= _TOLERANCE for known in supported[kind]):
                issues.append(Issue("REASON_NUMBER_UNSUPPORTED", Severity.FAIL,
                                    f"the reason states '{match.group(0).strip()}', which matches no value in the data for '{meal.name}'"))
    return issues


def verify(recommendation: Recommendation, pool: dict[int, Meal], constraints: Constraints,
           known_sources: set[str]) -> VerifyReport:
    """Check a draft against the meals retrieved in this run (pool) and the user's hard constraints."""
    report = VerifyReport()

    if recommendation.meal_id is None:
        return report  # "nothing fits" is a valid answer; no meal to check

    meal = pool.get(recommendation.meal_id)
    if meal is None:
        report.issues.append(Issue("MEAL_NOT_RETRIEVED", Severity.FAIL,
                                   f"meal_id {recommendation.meal_id} was not returned by search_meals in this run"))
        return report

    report.issues.extend(check_hard_constraints(meal, constraints))
    report.issues.extend(check_reason_numbers(recommendation.reason, meal, constraints))

    for index, claim in enumerate(recommendation.claims):
        if claim.source is ClaimSource.FROM_CANDIDATE:
            report.issues.extend(_check_candidate_claim(index, claim, meal))
        elif claim.source is ClaimSource.FROM_GUIDELINE:
            if not claim.guideline_ref or claim.guideline_ref not in known_sources:
                report.issues.append(Issue(
                    "UNKNOWN_SOURCE", Severity.FAIL,
                    f"guideline_ref '{claim.guideline_ref}' is not a source label returned by lookup_nutrition", index))
        # FROM_PREFERENCE and INFERRED are intentionally not checked.
    return report
