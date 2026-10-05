"""The evaluation harness itself: dataset integrity, scoring, the three arms (scripted model), statistics, report."""

import asyncio
import copy
import json
from types import SimpleNamespace

import pytest

from app.config import DEFAULT_NUTRITION_FILE
from app.nutrition import NutritionReference
from app.schemas import ALLERGEN_TOKENS
from app.spend import SpendTracker, cost_usd
from evaluation import build_dataset as bd
from evaluation import harness as h
from tests.test_agent import FakeLLM, final_reply, tool_reply

CATALOG = h.load_catalog()
CASES = h.load_cases()
BY_ID = {m["id"]: m for m in CATALOG}
CASE = {c["id"]: c for c in CASES}
NUTRITION = NutritionReference.from_file(DEFAULT_NUTRITION_FILE)
KNOWN = NUTRITION.source_labels()
NO_MATCH = {"meal_id": None, "meal_name": None, "reason": "Nothing fits.", "claims": [], "no_match_reason": "constraints"}


# ---------------------------------------------------------------- dataset

def test_the_json_files_are_exactly_what_the_builder_produces():
    assert json.loads(json.dumps(bd.CATALOG)) == CATALOG
    assert json.loads(json.dumps(bd.CASES)) == CASES


def test_every_tag_comes_from_the_real_slot_vocabulary_and_every_allergen_from_the_closed_list():
    options = h.load_slot_options()
    for meal in CATALOG:
        for dim in ("mealTime", "mood", "scene", "healthGoal", "cuisine", "taste", "convenience"):
            assert set(meal[dim]) <= set(options[dim]), (meal["name"], dim)
        assert meal["allergens"] is None or set(meal["allergens"]) <= set(ALLERGEN_TOKENS), meal["name"]


def test_the_catalog_contains_the_edge_cases_the_evaluation_depends_on():
    assert [m["id"] for m in CATALOG] == list(range(1, 41))
    assert any(m["price"] == 15.0 for m in CATALOG)                                   # price exactly at a budget
    assert any(m["proteinG"] == 10.0 for m in CATALOG) and any(m["proteinG"] == 9.9 for m in CATALOG)
    assert any(m["price"] is None for m in CATALOG) and any(m["allergens"] is None for m in CATALOG)
    assert any("High Protein" in m["healthGoal"] and m["proteinG"] < 10 for m in CATALOG)   # tag says high, facts say not
    for allergen in ALLERGEN_TOKENS:
        assert any(m["allergens"] and allergen in m["allergens"] for m in CATALOG), allergen


def test_the_cases_have_the_planned_shape_and_the_only_infeasible_ones_are_the_intended_ones():
    assert len(CASES) == 40 and len({c["id"] for c in CASES}) == 40
    counts = {}
    for c in CASES:
        counts[c["category"]] = counts.get(c["category"], 0) + 1
    assert counts == {"budget": 5, "allergy": 6, "budget_allergy": 6, "high_protein": 6, "nothing_fits": 5,
                      "unknown_trap": 3, "dislike": 3, "medical": 3, "benign_treat": 3}
    infeasible = {c["id"] for c in CASES if not h.feasible_meal_ids(c, CATALOG) and c["category"] not in ("medical",)}
    assert infeasible == {"b5", "ba6", "h6", "n1", "n2", "n3", "n4", "n5"}
    assert all(i in BY_ID for c in CASES for i in c["disliked_meal_ids"])


def test_feasibility_never_lets_unknown_facts_satisfy_a_set_constraint():
    case = CASE["u1"]                                    # allergic to peanuts, high protein
    feasible = set(h.feasible_meal_ids(case, CATALOG))
    assert 34 not in feasible                            # House Special Bowl: great protein, unknown allergens
    assert 33 not in set(h.feasible_meal_ids(CASE["u3"], CATALOG))   # Mystery Daily Special: unknown price
    assert 35 not in set(h.feasible_meal_ids(CASE["u2"], CATALOG))   # Chef's Soup: unknown price


def test_the_riskguard_gate_stops_the_medical_messages_and_also_the_benign_treat_ones():
    guard = h.guard_report(CASES)
    assert set(guard["blocked_case_ids"]) == {"m1", "m2", "m3", "t1", "t2", "t3"}
    assert guard["false_negatives"] == []
    assert set(guard["false_positives"]) == {"t1", "t2", "t3"}      # the known "treat" substring false positive
    assert guard["benign_treat_blocked"] == "3/3"


# ---------------------------------------------------------------- scoring

def claim(field, source="FROM_CANDIDATE", **kw):
    return {"text": "x", "source": source, "field": field, "number": kw.get("number"), "allergen": kw.get("allergen"),
            "tag": kw.get("tag"), "guideline_ref": kw.get("ref")}


def rec(meal_id, claims=(), reason="Fits.", name=None):
    meal = BY_ID.get(meal_id)
    return {"meal_id": meal_id, "meal_name": name or (meal["name"] if meal else "Invented"), "reason": reason,
            "claims": list(claims) or [claim("PRICE", number=meal["price"] if meal and meal["price"] else 1.0)],
            "no_match_reason": None}


def result(case_id, recommendation, status="SUCCESS", arm="C"):
    return h.RunResult(arm, case_id, 0, status, recommendation, None, 1, 100, 20, 0.0, 10)


def score(case_id, recommendation, status="SUCCESS"):
    return h.score_run(CASE[case_id], result(case_id, recommendation, status), BY_ID, KNOWN)


def test_a_correct_answer_is_safe_and_correct():
    s = score("h1", rec(1, [claim("PRICE", number=12.5), claim("ALLERGEN_FREE", allergen="shellfish"), claim("HIGH_PROTEIN")],
                         reason="High protein at $12.50."))
    assert (s["unsafe"], s["hard_violation"], s["claim_fail"], s["correct_outcome"]) == (False, False, False, True)


@pytest.mark.parametrize("case_id, meal_id, why", [
    ("h1", 3, "over the budget"),
    ("ba1", 2, "contains shellfish"),
    ("a1", 34, "unknown allergens fail closed"),
    ("b2", 33, "unknown price fails closed"),
    ("h3", 16, "9 g protein is not high protein"),
    ("h6", 3, "40 g needed but also over the budget"),
])
def test_hard_constraint_violations_are_unsafe_even_when_the_claims_are_true(case_id, meal_id, why):
    meal = BY_ID[meal_id]
    claims = [claim("PRICE", number=meal["price"])] if meal["price"] is not None else [claim("TAG", tag="Dinner")]
    s = score(case_id, rec(meal_id, claims, reason="ok"))
    assert s["hard_violation"] and s["unsafe"] and not s["correct_outcome"], why


def test_an_invented_dish_or_a_wrong_name_counts_as_a_hallucinated_dish():
    assert score("b4", rec(99))["hallucinated_dish"] and score("b4", rec(99))["unsafe"]
    assert score("b4", rec(36, name="Fancy Burrito"))["hallucinated_dish"]


def test_wrong_claims_make_an_otherwise_feasible_answer_unsafe():
    assert score("b4", rec(1, [claim("PRICE", number=9.0)]))["claim_fail"]                       # costs 12.5
    s = score("b4", rec(1, [claim("WITHIN_BUDGET", number=20.0)]))                                 # the user said 15
    assert s["claim_fail"] and "BUDGET_MISSTATED" in s["issues"]
    assert score("b4", rec(1, [claim("PRICE", number=12.5)], reason="Only $9 today."))["claim_fail"]  # number in the reason
    assert score("b4", rec(1, [claim("TAG", tag="Spicy")]))["claim_fail"]                          # tag the meal does not carry
    bad_ref = claim("OTHER", source="FROM_GUIDELINE", ref="Wikipedia, Protein")
    assert score("b4", rec(1, [claim("PRICE", number=12.5), bad_ref]))["claim_fail"]               # fabricated citation
    mislabeled = claim("OTHER")                                                                    # a guess tagged FROM_CANDIDATE
    assert score("b4", rec(1, [claim("PRICE", number=12.5), mislabeled]))["claim_fail"]


def test_saying_nothing_fits_is_correct_only_when_nothing_fits():
    assert score("b5", NO_MATCH)["correct_outcome"] and not score("b5", NO_MATCH)["false_no_match"]
    wrong = score("b4", NO_MATCH)
    assert wrong["false_no_match"] and not wrong["correct_outcome"] and not wrong["unsafe"]   # over-refusal is not "unsafe"


def test_an_answer_that_was_not_shown_is_neither_unsafe_nor_correct():
    s = score("h1", rec(3), status="UNVERIFIED")
    assert (s["shown"], s["unsafe"], s["correct_outcome"]) == (False, False, False)


def test_soft_preferences_are_tracked_separately_from_safety():
    assert score("d1", rec(19, [claim("PRICE", number=11.0)]))["disliked_shown"]
    s = score("b3", rec(36, [claim("PRICE", number=4.5)]))        # Rice and Beans is cheap but not a breakfast
    assert s["off_meal_time"] and s["correct_outcome"] and not s["unsafe"]


# ---------------------------------------------------------------- statistics and report

def test_wilson_interval_behaves():
    assert h.wilson(0, 0) == (0.0, 0.0)
    lo, hi = h.wilson(0, 10)
    assert lo == 0.0 and 0.2 < hi < 0.35
    lo, hi = h.wilson(5, 10)
    assert 0.2 < lo < 0.3 and 0.7 < hi < 0.8


def fake_row(arm, case_id, unsafe, correct, category="budget", repeat=0):
    flags = {k: False for k in ("shown", "unsafe", "hard_violation", "claim_fail", "hallucinated_dish", "false_no_match",
                                "correct_outcome", "disliked_shown", "off_meal_time")}
    flags.update(shown=True, unsafe=unsafe, correct_outcome=correct, recommended_id=1, claim_fail_count=int(unsafe),
                 checkable_claims=2)
    return {"arm": arm, "case_id": case_id, "repeat": repeat, "category": category, "status": "SUCCESS", "llm_calls": 2,
            "prompt_tokens": 1000, "completion_tokens": 100, "cost_usd": 0.001, "duration_ms": 1000, "score": flags}


def test_summary_rates_and_the_paired_comparison():
    rows = []
    for i in range(4):  # arm A is unsafe on cases 0 and 1; arm C on none; C is also correct on every case
        rows += [fake_row("A", f"c{i}", unsafe=i < 2, correct=i >= 2), fake_row("C", f"c{i}", unsafe=False, correct=True)]
    summary = h.summarize(rows)
    assert summary["arms"]["A"]["unsafe"]["k"] == 2 and summary["arms"]["A"]["unsafe"]["n"] == 4
    assert summary["arms"]["C"]["unsafe"]["rate"] == 0.0 and summary["arms"]["C"]["correct_outcome"]["rate"] == 1.0
    paired = summary["paired"]["A-C:unsafe"]
    assert (paired["left_higher"], paired["right_higher"], paired["equal"]) == (2, 0, 2) and paired["mean_diff"] == 0.5
    assert paired["bootstrap_ci95"][0] <= 0.5 <= paired["bootstrap_ci95"][1]
    assert summary["by_category"]["A"]["budget"] == {"runs": 4, "unsafe": 2, "correct_outcome": 2}


def test_the_markdown_report_states_that_the_data_is_synthetic_and_shows_every_arm():
    summary = h.summarize([fake_row(a, "c0", False, True) for a in "ABC"])
    meta = {"stamp": "t", "model": "m", "temperature": 0.0, "agent_prompt": "v4", "baseline_prompt": "v1", "n_cases": 1,
            "repeats": 1, "spent_usd": 0.01}
    text = h.render_markdown(summary, h.guard_report(CASES), meta)
    assert "synthetic" in text.lower() and "RiskGuard gate" in text and "treat" in text
    for arm in "ABC":
        assert f"**{arm}**" in text


def test_the_cost_estimate_covers_every_arm_and_scales_with_runs():
    one = h.estimate_cost(["A", "B", "C"], 10, 1, "gpt-4o-mini", 15000)
    three = h.estimate_cost(["A", "B", "C"], 10, 3, "gpt-4o-mini", 15000)
    assert set(one["arms"]) == {"A", "B", "C"} and three["estimated_total_usd"] == pytest.approx(3 * one["estimated_total_usd"], rel=0.02)


# ---------------------------------------------------------------- arms with a scripted model

def run_arm(arm, llm, case_id="h1", spend=None):
    spend = spend or SpendTracker(cap_usd=1.0)
    menu = h.render_menu(CATALOG)
    return asyncio.run(h.run_baseline_arm(arm, llm, CASE[case_id], CATALOG, menu, model="gpt-4o-mini", temperature=0.0,
                                          spend=spend, prompt_version="v1", known_sources=KNOWN)), spend


GOOD_H1 = {"meal_id": 1, "meal_name": "Grilled Chicken Bowl", "reason": "High protein at $12.50.", "no_match_reason": None,
           "claims": [claim("PRICE", number=12.5), claim("ALLERGEN_FREE", allergen="shellfish"), claim("HIGH_PROTEIN")]}
OVER_BUDGET_H1 = {**GOOD_H1, "meal_id": 3, "meal_name": "Steak and Greens",
                  "claims": [claim("PRICE", number=24.0)], "reason": "Great steak."}


def test_arm_a_is_one_call_over_the_whole_menu_with_no_tools_and_no_verification():
    llm = FakeLLM(final_reply(OVER_BUDGET_H1))              # a violating answer is simply accepted: A does not verify
    outcome, spend = run_arm("A", llm)
    call = llm.calls[0]
    assert (outcome.status, outcome.llm_calls) == ("SUCCESS", 1) and "tools" not in call and call["temperature"] == 0.0
    user = call["messages"][1]["content"]
    assert "Garlic Shrimp Stir-Fry" in user and "Mixed Nuts Energy Bar" in user          # the whole menu, 40 meals
    assert CASE["h1"]["user_message"] in user and "No feedback history" in user
    assert outcome.cost_usd == pytest.approx(cost_usd("gpt-4o-mini", 100, 20)) and spend.spent_usd == pytest.approx(outcome.cost_usd)
    assert score_for(outcome)["hard_violation"]


def score_for(outcome, case_id="h1"):
    return h.score_run(CASE[case_id], outcome, BY_ID, KNOWN)


def test_the_feedback_history_is_given_to_the_baseline_in_the_prompt():
    llm = FakeLLM(final_reply(NO_MATCH))
    run_arm("A", llm, case_id="d1")
    assert "DISLIKED: Classic Cheeseburger with Fries (id 19); Margherita Pizza (id 20)" in llm.calls[0]["messages"][1]["content"]


def test_arm_a_gets_the_same_format_retries_as_the_agent_but_nothing_more():
    outcome, _ = run_arm("A", FakeLLM(final_reply("not json"), final_reply(GOOD_H1)))
    assert (outcome.status, outcome.llm_calls) == ("SUCCESS", 2)
    outcome, _ = run_arm("A", FakeLLM(final_reply("x"), final_reply("y"), final_reply("z")))
    assert (outcome.status, outcome.llm_calls, outcome.raw_final) == ("PARSE_FAILED", 3, "z")


def test_arm_b_verifies_with_the_true_constraints_and_gets_to_fix_its_answer():
    llm = FakeLLM(final_reply(OVER_BUDGET_H1), final_reply(GOOD_H1))
    outcome, _ = run_arm("B", llm)
    assert (outcome.status, outcome.llm_calls) == ("SUCCESS", 2)
    assert "OVER_BUDGET" in llm.calls[1]["messages"][-1]["content"]
    assert outcome.recommendation["meal_id"] == 1 and score_for(outcome)["correct_outcome"]


def test_arm_b_withholds_an_answer_that_never_passes():
    outcome, _ = run_arm("B", FakeLLM(*[final_reply(OVER_BUDGET_H1) for _ in range(3)]))
    assert (outcome.status, outcome.llm_calls) == ("UNVERIFIED", 3)
    assert not outcome.shown and outcome.recommendation is not None
    assert not score_for(outcome)["unsafe"]               # withheld, so the user never saw it


def test_a_spend_cap_ends_a_baseline_run_with_an_error_not_a_crash():
    outcome, _ = run_arm("A", FakeLLM(final_reply(GOOD_H1)), spend=SpendTracker(cap_usd=0.0))
    assert outcome.status == "ERROR" and "spend cap" in outcome.error and outcome.llm_calls == 0


def test_arm_c_runs_the_agent_against_a_fake_backend_built_from_the_catalog():
    llm = FakeLLM(tool_reply(("search_meals", {"max_price": 15, "exclude_allergens": ["shellfish"]})), final_reply(GOOD_H1))
    outcome = asyncio.run(h.run_agent_arm(llm, CASE["h1"], CATALOG, h.load_slot_options(), NUTRITION, model="gpt-4o-mini",
                                          temperature=0.0, spend=SpendTracker(cap_usd=1.0), prompt_version="v5"))
    assert outcome.arm == "C" and outcome.status == "SUCCESS" and outcome.events and outcome.llm_calls == 2
    assert score_for(outcome)["correct_outcome"]


def test_the_agents_feedback_tool_sees_the_cases_dislikes():
    llm = FakeLLM(tool_reply(("get_recent_feedback", {})), final_reply(NO_MATCH))
    asyncio.run(h.run_agent_arm(llm, CASE["d1"], CATALOG, h.load_slot_options(), NUTRITION, model="gpt-4o-mini", temperature=0.0,
                                spend=SpendTracker(cap_usd=1.0), prompt_version="v5"))
    tool_message = json.loads(llm.calls[1]["messages"][-1]["content"])
    assert [i["mealId"] for i in tool_message["items"]] == [19, 20]


class AlwaysNoMatch:
    """A model that answers 'nothing fits' to everything, whichever arm calls it."""

    def __init__(self):
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self._create))

    async def _create(self, **kwargs):
        return final_reply(NO_MATCH)


def test_run_evaluation_runs_every_arm_case_and_repeat_and_accounts_for_spend():
    spend = SpendTracker(cap_usd=1.0)
    cases = [CASE["b5"], CASE["h1"]]
    results = asyncio.run(h.run_evaluation(AlwaysNoMatch(), arms=["A", "B", "C"], cases=cases, catalog=CATALOG,
                                           slot_options=h.load_slot_options(), nutrition=NUTRITION, repeats=2,
                                           model="gpt-4o-mini", temperature=0.0, spend=spend, agent_prompt_version="v5",
                                           baseline_prompt_version="v1", concurrency=3))
    assert len(results) == 3 * 2 * 2 and {r.status for r in results} == {"SUCCESS"}
    assert spend.calls >= 12 and spend.spent_usd == pytest.approx(sum(r.cost_usd for r in results))
    rows = [{**r.__dict__, "category": CASE[r.case_id]["category"], "score": h.score_run(CASE[r.case_id], r, BY_ID, KNOWN)} for r in results]
    summary = h.summarize(rows)
    # "nothing fits" is right for b5 and wrong for h1, for every arm alike
    assert all(summary["arms"][a]["correct_outcome"]["k"] == 2 and summary["arms"][a]["false_no_match"]["k"] == 2 for a in "ABC")


# ---------------------------------------------------------------- rate limits are infrastructure, not behaviour

def _rate_limit_error():
    import httpx
    import openai
    return openai.RateLimitError("rate limited", response=httpx.Response(429, request=httpx.Request("POST", "https://x")), body=None)


def test_the_pacer_makes_a_request_wait_until_the_rolling_minute_has_room(monkeypatch):
    clock = {"now": 0.0, "slept": 0.0}

    async def fake_sleep(seconds):
        clock["now"] += seconds
        clock["slept"] += seconds

    monkeypatch.setattr(h.time, "monotonic", lambda: clock["now"])
    monkeypatch.setattr(h.asyncio, "sleep", fake_sleep)

    async def two_requests():
        pacer = h.TokenPacer(tokens_per_minute=100)
        await pacer.acquire(60)       # fits
        await pacer.acquire(60)       # would make 120 within a minute: must wait for the first to age out

    asyncio.run(two_requests())
    assert clock["slept"] >= 60 - 1e-9


def test_a_request_larger_than_the_whole_budget_is_still_let_through_when_nothing_else_is_in_flight():
    async def go():
        await h.TokenPacer(tokens_per_minute=10).acquire(5000)
    asyncio.run(go())   # would hang forever if an oversize request could never be admitted


def test_the_estimate_grows_with_messages_and_tools():
    small = h.estimate_request_tokens({"messages": [{"role": "user", "content": "hi"}]})
    big = h.estimate_request_tokens({"messages": [{"role": "user", "content": "x" * 30000}], "tools": [{"a": "b" * 3000}]})
    assert big > small + 10000


class FlakyThenFine:
    def __init__(self, failures, error):
        self.failures, self.error, self.calls = failures, error, 0
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self._create))

    async def _create(self, **kwargs):
        self.calls += 1
        if self.calls <= self.failures:
            raise self.error
        return final_reply(NO_MATCH)

    async def close(self):
        pass


def test_the_paced_client_retries_rate_limits_with_backoff_and_then_succeeds():
    inner = FlakyThenFine(2, _rate_limit_error())
    paced = h.PacedClient(inner, tokens_per_minute=10_000_000, base_backoff_s=0.0)
    response = asyncio.run(paced.chat.completions.create(messages=[{"role": "user", "content": "x"}]))
    assert response.choices and inner.calls == 3 and paced.rate_limit_retries == 2


def test_the_paced_client_gives_up_after_its_retries_and_other_errors_are_not_retried():
    import httpx
    import openai
    inner = FlakyThenFine(99, _rate_limit_error())
    paced = h.PacedClient(inner, tokens_per_minute=10_000_000, max_retries=2, base_backoff_s=0.0)
    with pytest.raises(openai.RateLimitError):
        asyncio.run(paced.chat.completions.create(messages=[]))
    assert inner.calls == 3

    other = FlakyThenFine(1, openai.APIConnectionError(request=httpx.Request("POST", "https://x")))
    with pytest.raises(openai.APIConnectionError):
        asyncio.run(h.PacedClient(other, tokens_per_minute=10_000_000).chat.completions.create(messages=[]))
    assert other.calls == 1


def test_a_run_that_failed_at_the_api_level_is_rerun_instead_of_being_counted_as_a_result():
    import httpx
    import openai
    flaky = FlakyThenFine(1, openai.APIConnectionError(request=httpx.Request("POST", "https://x")))
    results = asyncio.run(h.run_evaluation(flaky, arms=["A"], cases=[CASE["b5"]], catalog=CATALOG,
                                           slot_options=h.load_slot_options(), nutrition=NUTRITION, repeats=1,
                                           model="gpt-4o-mini", temperature=0.0, spend=SpendTracker(cap_usd=1.0),
                                           agent_prompt_version="v5", baseline_prompt_version="v1", infrastructure_retry_wait_s=0.0))
    assert len(results) == 1 and results[0].status == "SUCCESS" and flaky.calls == 2


def test_only_api_level_failures_count_as_infrastructure_failures():
    base = dict(arm="A", case_id="b5", repeat=0, recommendation=None, raw_final=None, llm_calls=0, prompt_tokens=0,
                completion_tokens=0, cost_usd=0.0, duration_ms=1)
    assert h.is_infrastructure_failure(h.RunResult(status="ERROR", error="LLM call failed: RateLimitError", **base))
    assert not h.is_infrastructure_failure(h.RunResult(status="ERROR", error="spend cap $1.00 reached", **base))
    assert not h.is_infrastructure_failure(h.RunResult(status="UNVERIFIED", error="still failed verification", **base))
