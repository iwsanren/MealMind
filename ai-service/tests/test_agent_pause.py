"""Human in the loop: pause to ask whether the budget may be raised, save the paused run, continue it later
(even "in another process": every step below uses a fresh event loop, a fresh SQLite connection and fresh tool objects)."""

import pytest
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver

from app.agent import Status
from app.agent_graph import (RunFinished, RunNotFound, build_agent_graph, read_paused, resume_agent_graph,
                             run_agent_graph)
from app.fake_backend import FakeBackend
from app.tools import AgentTools, RunContext
from tests.conftest import SLOT_OPTIONS, meal, run
from tests.test_agent import NO_MATCH, NUTRITION, FakeLLM, final_reply, recommendation, tool_reply

TOO_CHEAP = ("search_meals", {"max_price": 3, "exclude_allergens": ["shellfish"]})
MESSAGE = "dinner under $3, allergic to shellfish"
KW = dict(model="gpt-4o-mini", system_prompt="SYSTEM", prompt_version="v5")


def fresh_tools(backend, user_id=7):
    return AgentTools(backend, NUTRITION, RunContext(user_id=user_id))


def start(db, backend, llm, *, user_id=7, **kw):
    """First process: run until the pause (or the end)."""
    async def go():
        async with AsyncSqliteSaver.from_conn_string(str(db)) as saver:
            return await run_agent_graph(llm, fresh_tools(backend, user_id), MESSAGE, checkpointer=saver,
                                         allow_questions=True, meta={"session_id": "sess_1", "user_id": user_id}, **KW, **kw)
    return run(go())


def resume(db, backend, llm, decision, *, thread_id, user_id=7, tools=None, **kw):
    """Another process: new loop, new connection to the same file, new tool objects."""
    async def go():
        async with AsyncSqliteSaver.from_conn_string(str(db)) as saver:
            return await resume_agent_graph(llm, tools or fresh_tools(backend, user_id), thread_id=thread_id,
                                            decision=decision, checkpointer=saver, user_id=user_id,
                                            model="gpt-4o-mini", prompt_version="v5", **kw)
    return run(go())


@pytest.fixture
def db(tmp_path):
    return tmp_path / "checkpoints.sqlite"


def paused(db, backend):
    llm = FakeLLM(tool_reply(TOO_CHEAP))
    result = start(db, backend, llm)
    assert result.status is Status.NEEDS_INPUT
    return result, llm


def test_an_empty_hard_search_with_a_budget_pauses_and_asks_instead_of_giving_up(db, backend):
    result, llm = paused(db, backend)

    assert result.recommendation is None and result.trace_id.startswith("run_")
    assert result.question["type"] == "relax_budget" and result.question["current_max_price"] == 3
    assert result.question["excluded_allergens"] == ["shellfish"]
    assert result.question["allergens_can_be_relaxed"] is False
    assert "Allergen limits stay" in result.question["message"]
    assert len(llm.calls) == 1                       # the model was not called again to "give up" on its own
    assert [e["type"] for e in result.events] == ["run_start", "llm_call", "tool_call"]   # no closing snapshot yet


def test_the_saved_state_holds_what_is_needed_to_continue(db, backend):
    result, _ = paused(db, backend)

    async def read():
        async with AsyncSqliteSaver.from_conn_string(str(db)) as saver:
            return await read_paused(saver, result.trace_id, user_id=7)
    saved = run(read())

    assert [m["role"] for m in saved["messages"]] == ["system", "user", "assistant", "tool"]
    assert saved["meta"] == {"session_id": "sess_1", "user_id": 7}
    assert saved["tool_context"]["max_price"] == 3 and saved["tool_context"]["exclude_allergens"] == ["shellfish"]
    assert saved["tool_context"]["empty_hard_searches"] == [[3, ["shellfish"]]]
    assert saved["result"] is None and saved["asks"] == 0


def test_raising_the_budget_in_a_new_process_continues_from_the_pause_and_finishes(db, backend):
    first, _ = paused(db, backend)
    llm = FakeLLM(tool_reply(("search_meals", {"max_price": 20, "exclude_allergens": ["shellfish"]})),
                  final_reply(recommendation(meal_id=1, price="12.5")))
    tools = fresh_tools(backend)            # brand new: it knows nothing about the first process

    second = resume(db, backend, llm, {"decision": "raise_budget", "max_price": 20}, thread_id=first.trace_id, tools=tools)

    assert second.status is Status.SUCCESS and second.recommendation.meal_id == 1
    assert second.trace_id == first.trace_id and second.rounds == 3     # 1 before the pause, 2 after
    types = [e["type"] for e in second.events]
    assert types == ["run_start", "llm_call", "tool_call", "interrupt", "resume", "llm_call", "tool_call", "llm_call", "verify", "final"]
    resume_event = second.events[4]
    assert resume_event["decision"] == "raise_budget" and resume_event["max_price"] == 20
    # the model was told, in plain words, what changed and what did not
    told = llm.calls[0]["messages"][-1]
    assert told["role"] == "user" and "€20" in told["content"] and "shellfish" in told["content"] and "NOT changed" in told["content"]
    # the budget the verifier enforces is now the user's new one; the allergen limit is unchanged
    assert tools.context.max_price == 20 and tools.context.exclude_allergens == {"shellfish"}
    assert second.usage.llm_calls == 3          # usage carried across the pause


def test_a_decline_with_a_comment_goes_back_to_the_model_which_must_answer_no_match(db, backend):
    first, _ = paused(db, backend)
    llm = FakeLLM(final_reply(NO_MATCH))

    second = resume(db, backend, llm, {"decision": "decline", "note": "no, I will cook at home"}, thread_id=first.trace_id)

    assert second.status is Status.SUCCESS and second.recommendation.meal_id is None
    assert llm.calls[0]["tool_choice"] == "none"                          # still forced to answer, not to search more
    told = llm.calls[0]["messages"]
    assert "does not want to raise the budget" in told[-2]["content"] and "cook at home" in told[-2]["content"]
    assert [e for e in second.events if e["type"] == "resume"][0]["note"] == "no, I will cook at home"


@pytest.mark.parametrize("answer", [
    {"decision": "raise_budget", "max_price": 3},          # not higher than now
    {"decision": "raise_budget", "max_price": 2},
    {"decision": "raise_budget", "max_price": 5000},       # absurd
    {"decision": "raise_budget"},                          # no number
    {"decision": "raise_budget", "max_price": "20"},       # not a number
    {"decision": "raise_budget", "max_price": True},
    {"decision": "allergens_off"},                         # not an option
    "yes please",                                          # not even an object
])
def test_an_answer_that_makes_no_sense_never_relaxes_anything(db, backend, answer):
    first, _ = paused(db, backend)
    llm = FakeLLM(final_reply(NO_MATCH))
    tools = fresh_tools(backend)

    second = resume(db, backend, llm, answer, thread_id=first.trace_id, tools=tools)

    assert second.status is Status.SUCCESS and second.recommendation.meal_id is None
    assert [e for e in second.events if e["type"] == "resume"][0]["decision"] == "invalid"
    assert tools.context.max_price == 3 and tools.context.empty_hard_searches      # nothing was loosened
    assert llm.calls[0]["tool_choice"] == "none"


def test_allergen_limits_stay_even_if_the_model_drops_them_after_a_raise(db, backend):
    first, _ = paused(db, backend)
    shrimp = recommendation(meal_id=2, price="14.0")     # Shrimp Pasta: shellfish
    llm = FakeLLM(tool_reply(("search_meals", {"max_price": 20, "exclude_allergens": []})), *[final_reply(shrimp)] * 3)

    second = resume(db, backend, llm, {"decision": "raise_budget", "max_price": 20}, thread_id=first.trace_id)

    assert second.status is Status.UNVERIFIED          # (the endpoint never shows an UNVERIFIED draft)
    assert second.verification["ok"] is False and "CONTAINS_ALLERGEN" in second.error


def test_the_budget_ceiling_is_the_users_new_number_not_whatever_the_model_searches_with(db, backend):
    first, _ = paused(db, backend)
    steak = recommendation(meal_id=5, price="40.0")      # $40 Luxury Steak, above the new $20 budget
    llm = FakeLLM(tool_reply(("search_meals", {"max_price": 100, "exclude_allergens": ["shellfish"]})), *[final_reply(steak)] * 3)

    second = resume(db, backend, llm, {"decision": "raise_budget", "max_price": 20}, thread_id=first.trace_id)

    assert second.status is Status.UNVERIFIED          # the model searched up to $100, the verifier still enforces $20


def test_no_pause_when_only_allergens_ruled_everything_out(db):
    only_milk = FakeBackend([meal(1, "Cheese Pizza", allergens=["milk"])], slot_options=SLOT_OPTIONS)
    llm = FakeLLM(tool_reply(("search_meals", {"exclude_allergens": ["milk"]})), final_reply(NO_MATCH))

    result = start(db, only_milk, llm)

    assert result.status is Status.SUCCESS and result.recommendation.meal_id is None     # nothing to offer to relax


def test_after_two_questions_the_run_stops_asking(db, backend):
    first, _ = paused(db, backend)
    llm2 = FakeLLM(tool_reply(("search_meals", {"max_price": 4, "exclude_allergens": ["shellfish"]})))
    second = resume(db, backend, llm2, {"decision": "raise_budget", "max_price": 4}, thread_id=first.trace_id)
    assert second.status is Status.NEEDS_INPUT and second.question["current_max_price"] == 4       # still nothing: asks again

    llm3 = FakeLLM(tool_reply(("search_meals", {"max_price": 5, "exclude_allergens": ["shellfish"]})), final_reply(NO_MATCH))
    third = resume(db, backend, llm3, {"decision": "raise_budget", "max_price": 5}, thread_id=first.trace_id)
    assert third.status is Status.SUCCESS and third.recommendation.meal_id is None                 # asked twice already
    assert len([e for e in third.events if e["type"] == "interrupt"]) == 2


def test_resuming_an_unknown_run_is_not_found(db, backend):
    with pytest.raises(RunNotFound):
        resume(db, backend, FakeLLM(), {"decision": "decline"}, thread_id="run_does_not_exist")


def test_resuming_a_run_that_already_finished_is_refused(db, backend):
    first, _ = paused(db, backend)
    resume(db, backend, FakeLLM(final_reply(NO_MATCH)), {"decision": "decline"}, thread_id=first.trace_id)

    with pytest.raises(RunFinished):
        resume(db, backend, FakeLLM(), {"decision": "decline"}, thread_id=first.trace_id)


def test_someone_elses_paused_run_looks_like_an_unknown_one(db, backend):
    first, _ = paused(db, backend)
    with pytest.raises(RunNotFound):
        resume(db, backend, FakeLLM(), {"decision": "decline"}, thread_id=first.trace_id, user_id=8)


def test_asking_questions_without_somewhere_to_save_the_run_is_refused(backend):
    definitions = run(fresh_tools(backend).definitions())
    with pytest.raises(ValueError, match="checkpointer"):
        build_agent_graph(FakeLLM(), fresh_tools(backend), definitions, model="gpt-4o-mini", temperature=0.0, max_rounds=8,
                          max_format_retries=2, max_verify_retries=2, spend=None, allow_questions=True)


def test_without_the_option_the_old_behaviour_is_unchanged(db, backend):
    llm = FakeLLM(tool_reply(TOO_CHEAP), final_reply(NO_MATCH))

    async def go():
        async with AsyncSqliteSaver.from_conn_string(str(db)) as saver:
            result = await run_agent_graph(llm, fresh_tools(backend), MESSAGE, checkpointer=saver, **KW)  # allow_questions off
            return result, await saver.aget_tuple({"configurable": {"thread_id": result.trace_id}})
    result, saved = run(go())

    assert result.status is Status.SUCCESS and result.recommendation.meal_id is None
    assert saved is not None and saved.checkpoint["channel_values"]["result"]["status"] == "SUCCESS"
