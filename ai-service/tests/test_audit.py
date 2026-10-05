"""What is written down about a run, and what happens when writing fails."""

import json

from app.agent import MAX_RAW_FINAL_CHARS, Status
from app.audit import record_run
from app.backend import ToolError
from tests.conftest import run
from tests.test_agent import NO_MATCH, SEARCH_OK, FakeLLM, final_reply, go, recommendation, tool_reply


def event(result, kind):
    return next(e for e in result.events if e["type"] == kind)


def test_the_trace_starts_with_the_inputs_that_cannot_be_reconstructed_later(backend):
    result = go(FakeLLM(final_reply(NO_MATCH)), backend, user="vegan lunch under $9", temperature=0.0, prompt_version="v4")
    start = result.events[0]
    assert start["type"] == "run_start"
    assert (start["model"], start["temperature"], start["prompt_version"], start["max_rounds"]) == ("gpt-4o-mini", 0.0, "v4", 8)
    assert start["user_message"] == "vegan lunch under $9"


def test_the_final_event_is_a_snapshot_of_what_was_seen_and_decided(backend):
    result = go(FakeLLM(tool_reply(SEARCH_OK), final_reply(recommendation())), backend)
    final = result.events[-1]

    assert final["status"] == "SUCCESS" and final["error"] is None
    assert final["usage"]["llm_calls"] == 2 and final["usage"]["prompt_tokens"] == 200
    assert final["constraints"] == {"max_price": 20.0, "exclude_allergens": ["shellfish"]}
    assert [m["id"] for m in final["candidates_seen"]] == [1, 3]                  # the facts as the agent saw them
    assert final["candidates_seen"][0] == {"id": 1, "name": "Chicken Bowl", "price": 12.5, "protein_g": 38.0,
                                           "calories": 500, "allergens": ["milk"]}
    assert final["recommendation"]["meal_id"] == 1 and final["raw_final"].startswith("{")


def test_unknown_facts_stay_null_in_the_snapshot(backend):
    result = go(FakeLLM(tool_reply(("search_meals", {})), final_reply(NO_MATCH)), backend)
    stew = next(m for m in result.events[-1]["candidates_seen"] if m["id"] == 4)
    assert stew["price"] is None and stew["allergens"] is None


def test_a_failed_run_still_records_why_and_keeps_the_raw_text(backend):
    result = go(FakeLLM(final_reply("one"), final_reply("two"), final_reply("three" * 5000)), backend)
    final = result.events[-1]
    assert final["status"] == "PARSE_FAILED" and "not valid" in final["error"]
    assert len(final["raw_final"]) == MAX_RAW_FINAL_CHARS and final["recommendation"] is None


def test_every_event_is_json_serializable_and_the_payload_fits_the_backend_limits(backend):
    result = go(FakeLLM(tool_reply(SEARCH_OK, ("lookup_nutrition", {"topic": "protein"})), final_reply(recommendation())), backend)
    payload = result.trace_payload(session_id="sess_1", user_id=7)
    text = json.dumps(payload)
    assert len(text) < 20_000
    assert len(payload["traceId"]) <= 128 and len(payload["sessionId"]) <= 64 and len(payload["status"]) <= 32


# ---- recording ----

def test_record_run_posts_the_payload_to_the_backend(backend):
    result = go(FakeLLM(final_reply(NO_MATCH)), backend)
    outcome = run(record_run(backend, result, session_id="sess_9", user_id=7))

    assert outcome.written and outcome.error is None
    sent = backend.traces[0]
    assert (sent["traceId"], sent["sessionId"], sent["userId"], sent["status"]) == (result.trace_id, "sess_9", 7, "SUCCESS")
    assert len(sent["events"]) == len(result.events)


def test_a_backend_that_rejects_the_trace_does_not_lose_the_answer(backend):
    result = go(FakeLLM(final_reply(NO_MATCH)), backend)

    async def refuse(payload):
        raise ToolError("backend returned HTTP 500: boom")

    backend.write_trace = refuse
    outcome = run(record_run(backend, result, session_id="sess_9", user_id=7))

    assert not outcome.written and "HTTP 500" in outcome.error
    assert result.status is Status.SUCCESS and result.recommendation is not None  # the user still gets the answer


def test_each_run_gets_its_own_trace_id(backend):
    a = go(FakeLLM(final_reply(NO_MATCH)), backend)
    b = go(FakeLLM(final_reply(NO_MATCH)), backend)
    assert a.trace_id != b.trace_id
    run(record_run(backend, a, session_id="s", user_id=7))
    run(record_run(backend, b, session_id="s", user_id=7))
    assert len(backend.traces) == 2


def test_recording_the_same_run_twice_is_rejected_by_the_backend_not_silently_duplicated(backend):
    a = go(FakeLLM(final_reply(NO_MATCH)), backend)
    assert run(record_run(backend, a, session_id="s", user_id=7)).written
    second = run(record_run(backend, a, session_id="s", user_id=7))
    assert not second.written and "already exists" in second.error
