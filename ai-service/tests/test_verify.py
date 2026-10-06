import pytest

from app.schemas import Claim, ClaimField, ClaimSource, Constraints, Meal, Recommendation
from app.verify import HIGH_PROTEIN_G_PER_SERVING, Severity, check_hard_constraints, check_reason_numbers, verify

KNOWN = {'DGA 2025-2030, "Prioritize Protein Foods at Every Meal"'}


def meal(**overrides) -> Meal:
    base = dict(id=1, name="Chicken Bowl", price=12.5, proteinG=38.0, calories=520, allergens=["milk"],
                healthGoal=["High Protein"], mealTime=["Dinner"])
    base.update(overrides)
    return Meal.model_validate(base)


def rec(*claims: Claim, meal_id=1) -> Recommendation:
    return Recommendation(meal_id=meal_id, meal_name="Chicken Bowl", reason="Because.", claims=list(claims))


_NUMERIC = {ClaimField.PRICE, ClaimField.WITHIN_BUDGET, ClaimField.PROTEIN_G, ClaimField.CALORIES}


def claim(field: ClaimField, value=None, source=ClaimSource.FROM_CANDIDATE, ref=None, text="x") -> Claim:
    """value is routed into the typed field the claim uses: number, allergen or tag."""
    typed = {}
    if field in _NUMERIC and value is not None:
        typed["number"] = float(str(value).lstrip("$"))
    elif field is ClaimField.ALLERGEN_FREE:
        typed["allergen"] = value
    elif field is ClaimField.TAG:
        typed["tag"] = value
    return Claim(text=text, source=source, field=field, guideline_ref=ref, **typed)


def codes(report):
    return [(i.code, i.severity) for i in report.issues]


def run(recommendation, meals=None, constraints=None):
    pool = {m.id: m for m in (meals or [meal()])}
    return verify(recommendation, pool, constraints or Constraints(), KNOWN)


def test_a_fully_correct_draft_has_no_issues():
    report = run(rec(claim(ClaimField.PRICE, "12.5"), claim(ClaimField.WITHIN_BUDGET, "15"),
                     claim(ClaimField.PROTEIN_G, "38"), claim(ClaimField.HIGH_PROTEIN),
                     claim(ClaimField.ALLERGEN_FREE, "shellfish"), claim(ClaimField.TAG, "high protein"),
                     claim(ClaimField.CALORIES, "520")),
                 constraints=Constraints(max_price=15, exclude_allergens=["shellfish"]))
    assert report.ok and report.issues == []


def test_a_meal_that_was_never_retrieved_fails_even_if_it_exists_elsewhere():
    report = run(rec(claim(ClaimField.PRICE, "1"), meal_id=99))
    assert codes(report) == [("MEAL_NOT_RETRIEVED", Severity.FAIL)] and not report.ok


def test_no_match_recommendations_are_valid_and_unchecked():
    r = Recommendation(meal_id=None, meal_name=None, reason="Nothing fits.", claims=[], no_match_reason="all too expensive")
    assert run(r).ok


@pytest.mark.parametrize("price, expected", [(15.0, []), (15.01, ["OVER_BUDGET"]), (None, ["PRICE_UNKNOWN"])])
def test_budget_is_inclusive_and_unknown_price_fails(price, expected):
    issues = check_hard_constraints(meal(price=price), Constraints(max_price=15))
    assert [i.code for i in issues] == expected


def test_allergen_constraint_fails_on_a_match_and_on_unknown_but_passes_on_known_none():
    c = Constraints(exclude_allergens=["shellfish"])
    assert [i.code for i in check_hard_constraints(meal(allergens=["shellfish", "wheat"]), c)] == ["CONTAINS_ALLERGEN"]
    assert [i.code for i in check_hard_constraints(meal(allergens=None), c)] == ["ALLERGENS_UNKNOWN"]
    assert check_hard_constraints(meal(allergens=[]), c) == []
    assert check_hard_constraints(meal(allergens=None), Constraints()) == []  # no constraint set, nothing to violate


def test_numeric_claims_must_match_within_rounding():
    assert run(rec(claim(ClaimField.PRICE, "$12.50"))).ok
    assert codes(run(rec(claim(ClaimField.PRICE, "11")))) == [("PRICE_MISMATCH", Severity.FAIL)]
    assert codes(run(rec(claim(ClaimField.PROTEIN_G, "50")))) == [("PROTEIN_G_MISMATCH", Severity.FAIL)]
    assert codes(run(rec(claim(ClaimField.CALORIES, "900")))) == [("CALORIES_MISMATCH", Severity.FAIL)]
    assert codes(run(rec(claim(ClaimField.PRICE)))) == [("MISSING_VALUE", Severity.FAIL)]  # the number field was left empty


def test_within_budget_claim_fails_when_the_meal_costs_more():
    assert codes(run(rec(claim(ClaimField.WITHIN_BUDGET, "10")))) == [("OVER_BUDGET", Severity.FAIL)]


def test_high_protein_uses_the_fda_derived_threshold():
    assert HIGH_PROTEIN_G_PER_SERVING == 10.0
    assert run(rec(claim(ClaimField.HIGH_PROTEIN)), [meal(proteinG=10.0)]).ok
    assert codes(run(rec(claim(ClaimField.HIGH_PROTEIN)), [meal(proteinG=9.9)])) == [("NOT_HIGH_PROTEIN", Severity.FAIL)]


def test_claims_about_unknown_facts_are_unverifiable_warnings_not_passes():
    unknown = meal(price=None, proteinG=None, calories=None, allergens=None)
    report = run(rec(claim(ClaimField.PRICE, "10"), claim(ClaimField.HIGH_PROTEIN), claim(ClaimField.CALORIES, "400"),
                     claim(ClaimField.ALLERGEN_FREE, "milk")), [unknown])
    assert all(sev is Severity.UNVERIFIABLE for _, sev in codes(report)) and len(report.issues) == 4
    assert report.ok  # warnings alone do not fail the draft; hard constraints are what fail on unknowns


def test_the_original_mislabel_bug_a_guess_tagged_from_candidate_is_caught():
    # "does not contain any shellfish [FROM_CANDIDATE]" when the candidate data says nothing about allergens
    report = run(rec(claim(ClaimField.ALLERGEN_FREE, "shellfish")), [meal(allergens=None)])
    assert codes(report) == [("ALLERGENS_UNKNOWN", Severity.UNVERIFIABLE)]
    # and an unnamed, free-form candidate claim cannot hide behind the tag
    assert codes(run(rec(claim(ClaimField.OTHER, source=ClaimSource.FROM_CANDIDATE)))) == [("UNCHECKABLE_CANDIDATE_CLAIM", Severity.FAIL)]


def test_tag_claims_are_checked_case_insensitively_against_every_dimension():
    assert run(rec(claim(ClaimField.TAG, "dinner"))).ok
    assert codes(run(rec(claim(ClaimField.TAG, "Spicy")))) == [("TAG_NOT_ON_MEAL", Severity.FAIL)]


def test_guideline_claims_need_a_real_source_label_but_their_meaning_is_not_checked():
    ok = claim(ClaimField.OTHER, source=ClaimSource.FROM_GUIDELINE, ref='DGA 2025-2030, "Prioritize Protein Foods at Every Meal"')
    fake = claim(ClaimField.OTHER, source=ClaimSource.FROM_GUIDELINE, ref="Wikipedia, Protein")
    missing = claim(ClaimField.OTHER, source=ClaimSource.FROM_GUIDELINE)
    assert run(rec(ok)).ok
    assert codes(run(rec(fake))) == [("UNKNOWN_SOURCE", Severity.FAIL)]
    assert codes(run(rec(missing))) == [("UNKNOWN_SOURCE", Severity.FAIL)]


def test_preference_and_inferred_claims_are_never_checked():
    assert run(rec(claim(ClaimField.OTHER, source=ClaimSource.FROM_PREFERENCE),
                   claim(ClaimField.OTHER, source=ClaimSource.INFERRED))).ok


def test_issue_claim_index_points_at_the_offending_claim():
    report = run(rec(claim(ClaimField.PRICE, "12.5"), claim(ClaimField.PRICE, "99")))
    assert [i.claim_index for i in report.issues] == [1]


def test_recommendation_schema_rejects_inconsistent_drafts():
    with pytest.raises(ValueError, match="no_match_reason"):
        Recommendation(meal_id=None, meal_name=None, reason="x", claims=[])
    with pytest.raises(ValueError, match="claims must not be empty"):
        Recommendation(meal_id=1, meal_name="x", reason="x", claims=[])


@pytest.mark.parametrize("reason, expected", [
    ("Costs $12.50 and has 38 g of protein for 520 calories.", []),
    ("It has 38 grams of protein, well under your $15 budget.", []),          # 15 is the user's budget
    ("Packed with 45g of protein.", ["REASON_NUMBER_UNSUPPORTED"]),          # the meal has 38 g
    ("Only $9 tonight.", ["REASON_NUMBER_UNSUPPORTED"]),                      # the meal costs 12.5
    ("About 800 kcal.", ["REASON_NUMBER_UNSUPPORTED"]),
    ("A tasty dinner you will enjoy.", []),                                   # no figures, nothing to check
    ("Has 20 g of fat.", ["REASON_NUMBER_UNSUPPORTED"]),                      # no fat data exists, so any grams figure must be protein
    # Euro writings: marker before or after the number, decimal point or comma, symbol or word.
    ("Costs €12.50 and has 38 g of protein.", []),
    ("Costs 12,50 € and has 38 g of protein.", []),
    ("Costs 12.50 euros, under your 15 euro budget.", []),                    # 15 is the user's budget
    ("Costs 12,5 EUR.", []),
    ("Only €9 tonight.", ["REASON_NUMBER_UNSUPPORTED"]),                      # the meal costs 12.5
    ("Only 9,50 € tonight.", ["REASON_NUMBER_UNSUPPORTED"]),
    ("Only 9 euros tonight.", ["REASON_NUMBER_UNSUPPORTED"]),
    ("Has 520 calories in 2 courses.", []),                                   # a bare number is not a price
])
def test_numbers_written_in_the_reason_must_match_the_data(reason, expected):
    issues = check_reason_numbers(reason, meal(), Constraints(max_price=15))
    assert [i.code for i in issues] == expected


def test_a_figure_in_the_reason_cannot_be_supported_when_the_fact_is_unknown():
    assert [i.code for i in check_reason_numbers("Only $9.", meal(price=None), Constraints())] == ["REASON_NUMBER_UNSUPPORTED"]


def test_verify_applies_the_reason_check_to_the_recommended_meal():
    r = Recommendation(meal_id=1, meal_name="x", reason="Only $9!", claims=[claim(ClaimField.TAG, "dinner")])
    assert [i.code for i in run(r).issues] == ["REASON_NUMBER_UNSUPPORTED"]
