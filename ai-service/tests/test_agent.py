"""The agent loop, driven by a scripted fake model (no network, no cost). Each test prints the round-by-round
timeline of what happened; run `pytest -s tests/test_agent.py` to see them.

Every test here runs twice, against the hand-written loop (app/agent.py) and the LangGraph version
(app/agent_graph.py): the same scenarios are the proof that the rewrite behaves identically."""

import copy
import json
from types import SimpleNamespace

import httpx
import openai
import pytest

from app.agent import AgentRun, Status, format_timeline, response_format, run_agent
from app.agent_graph import run_agent_graph
from app.backend import ToolError
from app.config import DEFAULT_NUTRITION_FILE
from app.nutrition import NutritionReference
from app.prompts import load_agent_prompt
from app.spend import SpendTracker, cost_usd
from app.tools import AgentTools, RunContext
from tests.conftest import run

NUTRITION = NutritionReference.from_file(DEFAULT_NUTRITION_FILE)


# ---- scripted fake model ----

def tool_reply(*calls: tuple[str, dict], prompt=100, completion=20):
    """A model reply that asks for tools. Every call gets an id like call_0, call_1 within the reply."""
    message = SimpleNamespace(content=None, tool_calls=[
        SimpleNamespace(id=f"call_{i}", function=SimpleNamespace(name=name, arguments=json.dumps(args)))
        for i, (name, args) in enumerate(calls)])
    return _response(message, "tool_calls", prompt, completion)


def final_reply(content, prompt=100, completion=20):
    return _response(SimpleNamespace(content=content if isinstance(content, str) else json.dumps(content), tool_calls=None),
                     "stop", prompt, completion)


def _response(message, finish, prompt, completion):
    return SimpleNamespace(choices=[SimpleNamespace(message=message, finish_reason=finish)],
                           usage=SimpleNamespace(prompt_tokens=prompt, completion_tokens=completion))


class FakeLLM:
    def __init__(self, *script):
        self.script = list(script)
        self.calls: list[dict] = []
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self._create))

    async def _create(self, **kwargs):
        self.calls.append(copy.deepcopy(kwargs))
        item = self.script.pop(0)  # IndexError here means the loop called the model more often than scripted
        if isinstance(item, Exception):
            raise item
        return item


def recommendation(meal_id=1, price="12.5", extra_claims=(), reason="High protein and within budget."):
    claims = [{"text": f"${price}", "source": "FROM_CANDIDATE", "field": "PRICE", "number": float(price),
               "allergen": None, "tag": None, "guideline_ref": None},
              *extra_claims]
    return {"meal_id": meal_id, "meal_name": "Chicken Bowl", "reason": reason, "claims": claims, "no_match_reason": None}


NO_MATCH = {"meal_id": None, "meal_name": None, "reason": "Nothing fits your budget.", "claims": [],
            "no_match_reason": "every meal is over budget"}
SEARCH_OK = ("search_meals", {"max_price": 20, "exclude_allergens": ["shellfish"]})


ENGINES = {"handwritten": run_agent, "langgraph": run_agent_graph}
_engine = {"run": run_agent}


@pytest.fixture(autouse=True, params=list(ENGINES))
def engine(request):
    _engine["run"] = ENGINES[request.param]
    yield request.param
    _engine["run"] = run_agent


def go(llm, backend, user="High protein dinner under $20, allergic to shellfish", **kw) -> AgentRun:
    tools = AgentTools(backend, NUTRITION, RunContext(user_id=7))
    result = run(_engine["run"](llm, tools, user, model="gpt-4o-mini", system_prompt="SYSTEM", **kw))
    print("\n" + format_timeline(result))
    return result


# ---- the loop ----

def test_a_model_that_answers_directly_needs_one_round_and_no_tools(backend):
    result = go(FakeLLM(final_reply(NO_MATCH)), backend)
    assert (result.status, result.rounds) == (Status.SUCCESS, 1)
    assert result.recommendation.meal_id is None
    assert [e["type"] for e in result.events] == ["run_start", "llm_call", "verify", "final"]


def test_parallel_tool_calls_are_all_run_and_all_returned_before_the_next_model_call(backend):
    llm = FakeLLM(tool_reply(SEARCH_OK, ("lookup_nutrition", {"topic": "protein"})), final_reply(recommendation()))
    result = go(llm, backend)

    assert result.status is Status.SUCCESS and result.rounds == 2
    second_call = llm.calls[1]["messages"]
    assistant, tool_a, tool_b = second_call[-3:]
    assert assistant["role"] == "assistant" and [c["id"] for c in assistant["tool_calls"]] == ["call_0", "call_1"]
    assert (tool_a["role"], tool_a["tool_call_id"]) == ("tool", "call_0")  # one role=tool message per request, matched by id
    assert (tool_b["role"], tool_b["tool_call_id"]) == ("tool", "call_1")
    assert json.loads(tool_a["content"])["count"] == 2 and "passages" in json.loads(tool_b["content"])
    assert [e["tool"] for e in result.events if e["type"] == "tool_call"] == ["search_meals", "lookup_nutrition"]


def test_a_failing_tool_is_reported_to_the_model_and_the_loop_survives(backend):
    async def broken(body):
        raise ToolError("backend returned HTTP 500: boom")

    backend.search_meals = broken
    llm = FakeLLM(tool_reply(SEARCH_OK), final_reply(NO_MATCH))
    result = go(llm, backend)

    tool_message = llm.calls[1]["messages"][-1]
    assert tool_message["role"] == "tool" and "HTTP 500" in json.loads(tool_message["content"])["error"]
    assert result.status is Status.SUCCESS
    assert next(e for e in result.events if e["type"] == "tool_call")["is_error"] is True


def test_the_round_limit_stops_a_model_that_never_stops_calling_tools(backend):
    llm = FakeLLM(*[tool_reply(("search_meals", {})) for _ in range(8)])
    result = go(llm, backend)
    assert (result.status, result.rounds, len(llm.calls)) == (Status.MAX_ROUNDS, 8, 8)
    assert "8 model calls" in result.error
    assert (result.events[-1]["type"], result.events[-1]["round"], result.events[-1]["status"]) == ("final", 8, "MAX_ROUNDS")


def test_the_round_limit_is_configurable(backend):
    result = go(FakeLLM(*[tool_reply(("search_meals", {})) for _ in range(2)]), backend, max_rounds=2)
    assert (result.status, result.rounds) == (Status.MAX_ROUNDS, 2)


def test_model_calls_carry_tools_a_strict_response_format_and_the_fixed_temperature(backend):
    llm = FakeLLM(final_reply(NO_MATCH))
    go(llm, backend, temperature=0.0)
    call = llm.calls[0]
    assert call["model"] == "gpt-4o-mini" and call["temperature"] == 0.0
    assert [t["function"]["name"] for t in call["tools"]][0] == "search_meals"
    assert call["response_format"]["json_schema"]["strict"] is True
    assert call["messages"][0] == {"role": "system", "content": "SYSTEM"}


# ---- malformed final answers ----

def test_an_invalid_final_answer_is_corrected_with_the_error_text(backend):
    llm = FakeLLM(final_reply("Sure! Try the chicken bowl."), final_reply(NO_MATCH))
    result = go(llm, backend)
    assert result.status is Status.SUCCESS and result.rounds == 2
    correction = llm.calls[1]["messages"][-1]
    assert correction["role"] == "user" and "not valid" in correction["content"]
    assert [e["type"] for e in result.events].count("format_retry") == 1


def test_the_loop_gives_up_after_two_corrections_and_keeps_the_raw_text(backend):
    llm = FakeLLM(final_reply("one"), final_reply("two"), final_reply("three"))
    result = go(llm, backend)
    assert (result.status, result.rounds, result.raw_final) == (Status.PARSE_FAILED, 3, "three")
    assert result.recommendation is None and "corrections used: 2" in result.error


def test_cross_field_schema_rules_are_reported_per_field(backend):
    broken = {**NO_MATCH, "no_match_reason": None}  # meal_id is null but no reason given
    llm = FakeLLM(final_reply(broken), final_reply(NO_MATCH))
    go(llm, backend)
    assert "no_match_reason" in llm.calls[1]["messages"][-1]["content"]


# ---- enforced verification ----

def test_a_wrong_claim_is_fed_back_and_the_model_gets_to_fix_it(backend):
    llm = FakeLLM(tool_reply(SEARCH_OK), final_reply(recommendation(price="9")), final_reply(recommendation(price="12.5")))
    result = go(llm, backend)

    assert result.status is Status.SUCCESS and result.rounds == 3
    feedback = llm.calls[2]["messages"][-1]["content"]
    assert "Verification failed" in feedback and "PRICE_MISMATCH" in feedback
    assert [e["ok"] for e in result.events if e["type"] == "verify"] == [False, True]


def test_an_answer_that_never_passes_ends_unverified_and_must_not_be_shown(backend):
    llm = FakeLLM(tool_reply(SEARCH_OK), *[final_reply(recommendation(price="9")) for _ in range(3)])
    result = go(llm, backend)
    assert result.status is Status.UNVERIFIED and result.rounds == 4
    assert result.recommendation is not None and "PRICE_MISMATCH" in result.error  # kept for the trace, flagged unsafe


def test_recommending_a_meal_that_was_never_retrieved_is_rejected(backend):
    llm = FakeLLM(final_reply(recommendation(meal_id=1)), final_reply(NO_MATCH))
    result = go(llm, backend)
    assert "MEAL_NOT_RETRIEVED" in llm.calls[1]["messages"][-1]["content"] and result.status is Status.SUCCESS


def test_a_meal_that_violates_the_hard_constraints_cannot_pass_even_with_correct_claims(backend):
    # The model searched without the allergy, then recommended the shrimp dish; the user's allergy was applied in a
    # later search, so the verifier (union of constraints) must reject it.
    llm = FakeLLM(tool_reply(("search_meals", {})), tool_reply(("search_meals", {"exclude_allergens": ["shellfish"]})),
                  final_reply(recommendation(meal_id=2, price="14")), final_reply(recommendation(meal_id=1)))
    result = go(llm, backend)
    assert "CONTAINS_ALLERGEN" in llm.calls[3]["messages"][-1]["content"]
    assert result.status is Status.SUCCESS and result.recommendation.meal_id == 1


# ---- failures outside the model's control ----

def test_an_llm_failure_ends_the_run_with_an_error_status_instead_of_raising(backend):
    error = openai.APIConnectionError(request=httpx.Request("POST", "https://api.openai.com/v1/chat/completions"))
    result = go(FakeLLM(error), backend)
    assert result.status is Status.ERROR and "APIConnectionError" in result.error and result.recommendation is None


def test_the_spend_cap_blocks_the_next_call(backend):
    spend = SpendTracker(cap_usd=0.05)
    llm = FakeLLM(tool_reply(("search_meals", {}), prompt=1_000_000, completion=0), final_reply(NO_MATCH))
    result = go(llm, backend, spend=spend)
    assert result.status is Status.ERROR and "spend cap" in result.error
    assert len(llm.calls) == 1  # the second call was never made


def test_a_backend_that_is_down_at_the_start_is_an_error_not_a_crash(backend):
    backend.fail_with = ToolError("backend unreachable (ConnectError)")
    result = go(FakeLLM(), backend)
    assert result.status is Status.ERROR and result.rounds == 0 and "tool definitions" in result.error


# ---- bookkeeping ----

def test_tokens_and_cost_are_accumulated_across_rounds(backend):
    llm = FakeLLM(tool_reply(SEARCH_OK, prompt=1000, completion=50), final_reply(recommendation(), prompt=2000, completion=100))
    result = go(llm, backend)
    assert (result.usage.llm_calls, result.usage.prompt_tokens, result.usage.completion_tokens) == (2, 3000, 150)
    assert result.usage.cost_usd == pytest.approx(cost_usd("gpt-4o-mini", 3000, 150))


def test_the_trace_payload_matches_the_internal_api_contract_and_is_json_serializable(backend):
    result = go(FakeLLM(tool_reply(SEARCH_OK), final_reply(recommendation())), backend)
    payload = result.trace_payload(session_id="sess_1", user_id=7)
    json.dumps(payload)
    assert set(payload) == {"traceId", "sessionId", "userId", "status", "durationMs", "errorMessage", "events"}
    assert payload["status"] == "SUCCESS" and isinstance(payload["events"], list)
    assert run(backend.write_trace(payload))["eventCount"] == len(result.events)


def test_the_timeline_shows_round_tool_and_outcome(backend):
    text = format_timeline(go(FakeLLM(tool_reply(SEARCH_OK), final_reply(recommendation())), backend))
    assert "R1  model -> search_meals" in text and "tool search_meals" in text and "2 meals" in text
    assert "verify -> ok" in text and "== SUCCESS ==" in text


def test_the_response_format_is_a_strict_schema_with_every_field_required():
    schema = response_format()["json_schema"]["schema"]
    assert schema["required"] == ["meal_id", "meal_name", "reason", "claims", "no_match_reason"]
    assert schema["additionalProperties"] is False


def test_the_system_prompt_file_loads_and_states_the_hard_constraint_rules():
    prompt = load_agent_prompt("v1")
    assert "never drop exclude_allergens" in prompt.lower() and "never raise the budget" in prompt.lower()
    with pytest.raises(FileNotFoundError):
        load_agent_prompt("v999")


# ---- guards that keep a stuck model cheap ----

def test_an_exact_repeat_of_an_earlier_tool_call_is_not_executed_again(backend):
    same = ("search_meals", {"max_price": 100, "mood": ["Happy"]})  # empty because of the tag, so nothing is forced
    llm = FakeLLM(tool_reply(same), tool_reply(same), final_reply(NO_MATCH))
    result = go(llm, backend)

    searches = [c for c in backend.calls if c[0] == "search_meals"]
    assert len(searches) == 1                                   # the repeat never reached the backend
    skipped = llm.calls[2]["messages"][-1]
    assert skipped["role"] == "tool" and "duplicate call" in json.loads(skipped["content"])["error"]
    assert [e["summary"] for e in result.events if e["type"] == "tool_call"] == ["0 meals", "duplicate call skipped"]
    assert result.status is Status.SUCCESS


def test_the_last_round_forbids_tools_so_the_model_must_answer(backend):
    llm = FakeLLM(tool_reply(("search_meals", {"mood": ["Happy"]})), tool_reply(("search_meals", {"mood": ["Tired"]})), final_reply(NO_MATCH))
    result = go(llm, backend, max_rounds=3)

    assert "tool_choice" not in llm.calls[0] and "tool_choice" not in llm.calls[1]
    assert llm.calls[2]["tool_choice"] == "none"
    assert "no tool calls left" in llm.calls[2]["messages"][-1]["content"]
    assert (result.status, result.rounds) == (Status.SUCCESS, 3)


def test_a_single_round_budget_is_not_forced_because_the_model_has_not_searched_yet(backend):
    llm = FakeLLM(final_reply(NO_MATCH))
    go(llm, backend, max_rounds=1)
    assert "tool_choice" not in llm.calls[0]


def test_once_the_hard_constraints_are_proven_unsatisfiable_the_next_call_must_be_the_answer(backend):
    llm = FakeLLM(tool_reply(("search_meals", {"max_price": 3, "exclude_allergens": ["shellfish"]})), final_reply(NO_MATCH))
    result = go(llm, backend)

    assert "tool_choice" not in llm.calls[0]
    assert llm.calls[1]["tool_choice"] == "none"
    assert "searching further cannot help" in llm.calls[1]["messages"][-1]["content"]
    assert [e for e in result.events if e["type"] == "forced_final"] == [
        {"type": "forced_final", "round": 2, "reason": "hard_constraints_unsatisfiable"}]
    assert (result.status, result.rounds) == (Status.SUCCESS, 2)


def test_an_empty_search_that_used_tag_filters_does_not_force_the_answer(backend):
    llm = FakeLLM(tool_reply(("search_meals", {"max_price": 100, "mood": ["Happy"]})), final_reply(NO_MATCH))
    go(llm, backend)
    assert "tool_choice" not in llm.calls[1]  # the tags might be the problem, so the model may still loosen them


def test_the_forced_note_is_added_per_call_and_does_not_pile_up_in_the_history(backend):
    llm = FakeLLM(tool_reply(("search_meals", {"max_price": 3})), final_reply("not json"), final_reply(NO_MATCH))
    go(llm, backend)
    notes = [m["content"] for m in llm.calls[2]["messages"] if m["role"] == "user" and "searching further cannot help" in m["content"]]
    assert len(notes) == 1  # only the one attached to this call, not a copy from every earlier forced call


# ---- findings from the step-6 smoke run ----

def test_tools_are_switched_off_two_rounds_before_the_limit_so_corrections_still_fit(backend):
    llm = FakeLLM(*[tool_reply(("search_meals", {"mood": ["Happy"], "max_price": i + 10})) for i in range(5)], final_reply(NO_MATCH))
    go(llm, backend)                                  # default max_rounds=8: rounds 6-8 may only answer
    assert all("tool_choice" not in llm.calls[i] for i in range(5))
    assert llm.calls[5]["tool_choice"] == "none"


def test_an_answer_that_fails_verification_on_the_last_round_is_unverified_not_max_rounds(backend):
    llm = FakeLLM(tool_reply(SEARCH_OK), final_reply(recommendation(price="9")))
    result = go(llm, backend, max_rounds=2)
    assert (result.status, result.rounds) == (Status.UNVERIFIED, 2)
    assert result.recommendation is not None and "PRICE_MISMATCH" in result.error and not result.error.startswith("no final answer")


def test_an_invalid_answer_on_the_last_round_is_a_parse_failure(backend):
    result = go(FakeLLM(final_reply("nope")), backend, max_rounds=1)
    assert (result.status, result.rounds, result.raw_final) == (Status.PARSE_FAILED, 1, "nope")
