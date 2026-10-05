import json

from app.backend import ToolError
from app.config import DEFAULT_NUTRITION_FILE
from app.nutrition import NutritionReference
from app.tools import AgentTools, RunContext
from tests.conftest import run

NUTRITION = NutritionReference.from_file(DEFAULT_NUTRITION_FILE)
GOOD_REF = 'DGA 2025-2030, "Prioritize Protein Foods at Every Meal"'


def tools_for(backend, **context) -> AgentTools:
    return AgentTools(backend, NUTRITION, RunContext(user_id=7, **context))


def call(tools, name, args) -> tuple[dict, bool]:
    outcome = run(tools.execute(name, json.dumps(args)))
    return json.loads(outcome.content), outcome.is_error


def draft(meal_id=1, claims=None):
    return {"recommendation": {"meal_id": meal_id, "meal_name": "Chicken Bowl", "reason": "High protein and in budget.",
                               "claims": claims if claims is not None else [
                                   {"text": "$12.50", "source": "FROM_CANDIDATE", "field": "PRICE", "number": 12.5}]}}


# ---- definitions: what the model reads ----

def test_the_model_is_offered_three_tools_and_verification_is_enforced_by_the_loop_instead(backend):
    defs = run(tools_for(backend).definitions())
    assert [d["function"]["name"] for d in defs] == ["search_meals", "get_recent_feedback", "lookup_nutrition"]
    assert all(len(d["function"]["description"]) > 80 for d in defs)
    json.dumps(defs)  # must be serializable exactly as sent to the API


def test_verify_can_still_be_offered_to_the_model_as_a_fourth_tool(backend):
    tools = AgentTools(backend, NUTRITION, RunContext(user_id=7), model_can_verify=True)
    assert [d["function"]["name"] for d in run(tools.definitions())][-1] == "verify_recommendation"


def test_the_verifier_is_still_executable_by_the_loop_when_it_is_not_offered(backend):
    tools = tools_for(backend)
    call(tools, "search_meals", {})
    result, error = call(tools, "verify_recommendation", draft())
    assert not error and "ok" in result


def test_search_tags_are_restricted_to_the_real_vocabulary_and_identity_is_not_a_parameter(backend):
    search = run(tools_for(backend).definitions())[0]["function"]["parameters"]["properties"]
    assert search["health_goal"]["items"]["enum"] == ["High Protein", "Light", "Fat Loss"]
    assert "shellfish" in search["exclude_allergens"]["items"]["enum"]
    for forbidden in ("user_id", "source_mode", "exclude_meal_ids"):
        assert forbidden not in search  # the caller decides who the user is, never the model


def test_the_verify_tool_schema_is_self_contained(backend):
    tools = AgentTools(backend, NUTRITION, RunContext(user_id=7), model_can_verify=True)
    verify_def = run(tools.definitions())[3]["function"]["parameters"]
    assert "$defs" not in json.dumps(verify_def) and "$ref" not in json.dumps(verify_def)
    assert "claims" in verify_def["properties"]["recommendation"]["properties"]


# ---- search_meals ----

def test_search_records_candidates_and_tracks_the_tightest_constraints(backend):
    tools = tools_for(backend)
    result, error = call(tools, "search_meals", {"max_price": 20, "exclude_allergens": ["shellfish"]})
    assert not error
    # meal 2 has shellfish, meal 4 has unknown facts, meal 5 costs 40: only 1 and 3 survive the hard filters
    assert [m["id"] for m in result["meals"]] == [1, 3]
    assert set(tools.context.candidates) == {1, 3}

    call(tools, "search_meals", {"max_price": 13, "exclude_allergens": ["peanut"]})
    constraints = tools.context.constraints()
    assert constraints.max_price == 13                                # the tightest budget used in the run
    assert constraints.exclude_allergens == ["peanut", "shellfish"]   # the union: dropping one later does not unlock it


def test_search_sends_identity_from_the_context_not_from_the_model(backend):
    tools = tools_for(backend, source_mode="PUBLIC", exclude_meal_ids=[2])
    call(tools, "search_meals", {"health_goal": ["High Protein"]})
    body = backend.calls[-1][1]
    assert (body["userId"], body["sourceMode"], body["excludeMealIds"], body["healthGoal"]) == (7, "PUBLIC", [2], ["High Protein"])


def test_a_model_supplied_user_id_is_rejected_by_validation(backend):
    result, error = call(tools_for(backend), "search_meals", {"user_id": 99})
    assert error and "user_id" in result["error"]
    assert not any(c[0] == "search_meals" for c in backend.calls)  # nothing reached the backend


def test_an_empty_hard_only_search_tells_the_model_to_stop_and_answer_no_match(backend):
    result, error = call(tools_for(backend), "search_meals", {"max_price": 1})
    assert not error and result["count"] == 0
    assert "Stop searching" in result["note"] and "meal_id null" in result["note"]


def test_an_empty_search_with_tag_filters_only_invites_loosening_the_tags(backend):
    result, _ = call(tools_for(backend), "search_meals", {"max_price": 100, "mood": ["Happy"]})  # no meal carries that mood
    assert result["count"] == 0 and "optional tag filters" in result["note"] and "Stop searching" not in result["note"]


def test_searches_that_cannot_find_anything_after_an_empty_hard_only_search_do_not_reach_the_backend(backend):
    tools = tools_for(backend)
    call(tools, "search_meals", {"max_price": 3, "exclude_allergens": ["shellfish"]})        # empty on hard constraints alone
    n = sum(1 for c in backend.calls if c[0] == "search_meals")

    same_plus_tags, _ = call(tools, "search_meals", {"max_price": 3, "exclude_allergens": ["shellfish"], "mood": ["Happy"]})
    tighter_budget, _ = call(tools, "search_meals", {"max_price": 2, "exclude_allergens": ["shellfish"]})
    more_allergens, _ = call(tools, "search_meals", {"max_price": 3, "exclude_allergens": ["shellfish", "milk"]})

    assert sum(1 for c in backend.calls if c[0] == "search_meals") == n          # none of the three hit the backend
    assert same_plus_tags["count"] == tighter_budget["count"] == more_allergens["count"] == 0
    assert "Stop searching" in same_plus_tags["note"]


def test_looser_hard_constraints_are_still_searched_for_real(backend):
    tools = tools_for(backend)
    call(tools, "search_meals", {"max_price": 3, "exclude_allergens": ["shellfish"]})
    n = sum(1 for c in backend.calls if c[0] == "search_meals")

    higher_budget, _ = call(tools, "search_meals", {"max_price": 20, "exclude_allergens": ["shellfish"]})
    fewer_allergens, _ = call(tools, "search_meals", {"max_price": 3})
    no_budget, _ = call(tools, "search_meals", {"exclude_allergens": ["shellfish"]})

    assert sum(1 for c in backend.calls if c[0] == "search_meals") == n + 3     # all three went to the backend
    assert higher_budget["count"] > 0 and no_budget["count"] > 0


def test_a_search_found_empty_by_inference_still_counts_toward_the_run_constraints(backend):
    tools = tools_for(backend)
    call(tools, "search_meals", {"max_price": 3, "exclude_allergens": ["shellfish"]})
    call(tools, "search_meals", {"max_price": 2, "exclude_allergens": ["shellfish", "milk"], "mood": ["Happy"]})
    assert tools.context.constraints().max_price == 2 and tools.context.constraints().exclude_allergens == ["milk", "shellfish"]


def test_null_facts_stay_null_in_what_the_model_sees(backend):
    result, _ = call(tools_for(backend), "search_meals", {})
    stew = next(m for m in result["meals"] if m["id"] == 4)
    assert stew["price"] is None and stew["allergens"] is None  # unknown must not look like 0 or safe


def test_backend_failures_become_error_results_not_exceptions(backend):
    backend.fail_with = ToolError("backend returned HTTP 500: boom")
    outcome = run(tools_for(backend).execute("search_meals", "{}"))
    assert outcome.is_error and "HTTP 500" in json.loads(outcome.content)["error"]


def test_invalid_arguments_name_the_offending_field(backend):
    result, error = call(tools_for(backend), "search_meals", {"max_price": -5})
    assert error and "max_price" in result["error"]


def test_malformed_argument_json_is_an_error_result(backend):
    outcome = run(tools_for(backend).execute("search_meals", "{not json"))
    assert outcome.is_error and "valid JSON" in json.loads(outcome.content)["error"]


def test_unknown_tools_are_reported_back(backend):
    result, error = call(tools_for(backend), "delete_everything", {})
    assert error and "unknown tool" in result["error"]


# ---- get_recent_feedback / lookup_nutrition ----

def test_recent_feedback_uses_the_context_user_and_the_limit(backend):
    backend.feedback = [{"userId": 7, "mealId": 2, "mealName": "Shrimp Pasta", "action": "DISLIKE"},
                        {"userId": 8, "mealId": 1, "mealName": "Chicken Bowl", "action": "LIKE"}]
    result, error = call(tools_for(backend), "get_recent_feedback", {"limit": 5})
    assert not error and [i["mealName"] for i in result["items"]] == ["Shrimp Pasta"]
    assert backend.calls[-1] == ("recent_feedback", (7, 5))


def test_lookup_nutrition_returns_cited_passages_and_says_when_nothing_matches():
    tools = AgentTools(None, NUTRITION, RunContext(user_id=7))  # this tool never touches the backend
    result, error = call(tools, "lookup_nutrition", {"topic": "protein"})
    assert not error and result["passages"][0]["source"] == GOOD_REF
    result, _ = call(tools, "lookup_nutrition", {"topic": "quantum chromodynamics"})
    assert result["passages"] == [] and "Do not make FROM_GUIDELINE" in result["note"]


# ---- verify_recommendation ----

def test_verify_passes_a_correct_draft_after_a_real_search(backend):
    tools = tools_for(backend)
    call(tools, "search_meals", {"max_price": 15, "exclude_allergens": ["shellfish"]})
    result, error = call(tools, "verify_recommendation", draft())
    assert not error and result["ok"] and result["constraints_checked"]["max_price"] == 15


def test_verify_catches_a_dish_seen_earlier_that_violates_the_constraints_added_later(backend):
    tools = tools_for(backend)
    call(tools, "search_meals", {})                                       # unconstrained: the shrimp dish enters the pool
    call(tools, "search_meals", {"exclude_allergens": ["shellfish"]})     # the user's allergy is applied afterwards
    result, _ = call(tools, "verify_recommendation", draft(meal_id=2))
    assert not result["ok"] and "CONTAINS_ALLERGEN" in [i["code"] for i in result["issues"]]


def test_verify_rejects_meals_that_were_never_searched(backend):
    result, _ = call(tools_for(backend), "verify_recommendation", draft(meal_id=1))
    assert not result["ok"] and result["issues"][0]["code"] == "MEAL_NOT_RETRIEVED"


def test_verify_runs_the_risk_check_on_the_reason_and_blocks_on_a_hit(backend):
    tools = tools_for(backend)
    call(tools, "search_meals", {})
    bad = draft()
    bad["recommendation"]["reason"] = "This will cure your diabetes."
    result, _ = call(tools, "verify_recommendation", bad)
    assert not result["ok"] and "RISK_BLOCKED" in [i["code"] for i in result["issues"]]
    assert backend.calls[-1] == ("risk_check", "This will cure your diabetes.")


def test_verify_fails_closed_when_the_risk_check_cannot_run(backend):
    tools = tools_for(backend)
    call(tools, "search_meals", {})

    async def down(text):
        raise ToolError("backend unreachable (ConnectError)")

    backend.risk_check = down
    result, _ = call(tools, "verify_recommendation", draft())
    assert not result["ok"] and "RISK_BLOCKED" in [i["code"] for i in result["issues"]]


def test_verify_reports_schema_problems_per_field(backend):
    result, error = call(tools_for(backend), "verify_recommendation",
                         {"recommendation": {"meal_id": 1, "meal_name": "x", "reason": "r", "claims": []}})
    assert error and "claims must not be empty" in result["error"]
    result, error = call(tools_for(backend), "verify_recommendation",
                         {"recommendation": {"meal_id": 1, "meal_name": "x", "reason": "r",
                                             "claims": [{"text": "t", "source": "FROM_MAGIC", "field": "PRICE"}]}})
    assert error and "claims.0.source" in result["error"]
