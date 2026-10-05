"""POST /v1/recommend: what the Java backend calls. A scripted fake model and the fake backend, no network, no cost."""

import asyncio

import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.config import Settings, get_settings
from app.main import app, get_backend, get_client, get_nutrition
from app.recommend import RecommendRequest, compose_user_message
from tests.test_agent import NO_MATCH, NUTRITION, SEARCH_OK, FakeLLM, final_reply, recommendation, tool_reply

BODY = {"user_message": "High protein dinner under $20, allergic to shellfish", "session_id": "sess_1", "user_id": 7,
        "source_mode": "PUBLIC", "slots": {"mealTime": ["Dinner"]}, "exclude_meal_ids": [3]}


@pytest.fixture(autouse=True)
def reset_overrides():
    yield
    app.dependency_overrides.clear()


def use(llm, backend, **settings):
    app.dependency_overrides[get_client] = lambda: llm
    app.dependency_overrides[get_backend] = lambda: backend
    app.dependency_overrides[get_nutrition] = lambda: NUTRITION
    app.dependency_overrides[get_settings] = lambda: Settings(_env_file=None, openai_api_key=SecretStr("sk-test"), **settings)
    return TestClient(app)


def test_a_verified_answer_is_returned_with_its_trace_id_and_the_trace_is_stored(backend):
    http = use(FakeLLM(tool_reply(SEARCH_OK), final_reply(recommendation())), backend)
    response = http.post("/v1/recommend", json=BODY)

    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "SUCCESS" and data["recommendation"]["meal_id"] == 1
    assert data["trace_written"] is True and data["trace_id"] == backend.traces[0]["traceId"]
    assert backend.traces[0]["sessionId"] == "sess_1" and backend.traces[0]["userId"] == 7
    assert data["model"] == "gpt-4o-mini" and data["llm_calls"] == 2 and data["cost_usd"] > 0


def test_the_callers_context_reaches_the_tools_not_the_model(backend):
    llm = FakeLLM(tool_reply(SEARCH_OK), final_reply(NO_MATCH))  # user 9 owns no personal meals, so the search is empty
    use(llm, backend).post("/v1/recommend", json={**BODY, "source_mode": "PERSONAL", "user_id": 9, "exclude_meal_ids": [1, 2]})

    search_body = next(payload for name, payload in backend.calls if name == "search_meals")
    assert search_body["sourceMode"] == "PERSONAL" and search_body["userId"] == 9 and search_body["excludeMealIds"] == [1, 2]
    # the model is only told the user's words plus a soft summary of the earlier slots
    assert llm.calls[0]["messages"][1]["content"].startswith("High protein dinner under $20")
    assert "mealTime: Dinner" in llm.calls[0]["messages"][1]["content"]


def test_a_legitimate_no_match_is_still_a_success_with_no_meal(backend):
    response = use(FakeLLM(final_reply(NO_MATCH)), backend).post("/v1/recommend", json=BODY)
    data = response.json()
    assert data["status"] == "SUCCESS" and data["recommendation"]["meal_id"] is None
    assert data["recommendation"]["no_match_reason"]


def test_an_answer_that_never_passes_verification_is_withheld(backend):
    bad = recommendation(meal_id=2, price="14.0")  # Shrimp Pasta: contains shellfish, which the search excluded
    llm = FakeLLM(tool_reply(SEARCH_OK), *[final_reply(bad)] * 5)
    response = use(llm, backend).post("/v1/recommend", json=BODY)
    data = response.json()

    assert response.status_code == 200
    assert data["status"] in {"UNVERIFIED", "MAX_ROUNDS"} and data["recommendation"] is None


def test_an_llm_failure_is_a_status_not_an_http_error(backend):
    class Boom:
        class chat:
            class completions:
                @staticmethod
                async def create(**kwargs):
                    import openai
                    raise openai.APIConnectionError(request=None)

    response = use(Boom, backend).post("/v1/recommend", json=BODY)
    assert response.status_code == 200
    assert response.json()["status"] == "ERROR" and response.json()["recommendation"] is None


def test_a_slow_run_is_cut_off_by_the_deadline(backend):
    class Slow:
        class chat:
            class completions:
                @staticmethod
                async def create(**kwargs):
                    await asyncio.sleep(5)

    response = use(Slow, backend, agent_deadline_s=0.2).post("/v1/recommend", json=BODY)
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ERROR" and "deadline" in data["error"] and data["recommendation"] is None


def test_an_unreachable_backend_is_reported_as_an_error_status(backend):
    from app.backend import ToolError
    backend.fail_with = ToolError("backend unreachable (ConnectError)")
    response = use(FakeLLM(), backend).post("/v1/recommend", json=BODY)
    assert response.status_code == 200 and response.json()["status"] == "ERROR"


@pytest.mark.parametrize("change", [
    {"user_message": ""}, {"user_message": "x" * 1001}, {"source_mode": "EVERYONE"}, {"user_id": 0},
    {"slots": {"flavour": ["Sweet"]}}, {"extra": "field"}, {"exclude_meal_ids": list(range(101))},
])
def test_bad_requests_are_rejected_before_any_model_call(backend, change):
    llm = FakeLLM()
    response = use(llm, backend).post("/v1/recommend", json={**BODY, **change})
    assert response.status_code == 422 and llm.calls == []


def test_without_an_api_key_the_endpoint_answers_503(backend):
    app.dependency_overrides[get_backend] = lambda: backend
    app.dependency_overrides[get_nutrition] = lambda: NUTRITION
    app.dependency_overrides[get_settings] = lambda: Settings(_env_file=None)
    assert TestClient(app).post("/v1/recommend", json=BODY).status_code == 503


def test_the_user_message_is_unchanged_when_there_are_no_slots():
    request = RecommendRequest(**{**BODY, "slots": {}})
    assert compose_user_message(request) == BODY["user_message"]


@pytest.mark.parametrize("engine", ["handwritten", "langgraph"])
def test_both_agent_engines_give_the_same_answer_through_the_endpoint(backend, engine):
    http = use(FakeLLM(tool_reply(SEARCH_OK), final_reply(recommendation())), backend, agent_engine=engine)
    data = http.post("/v1/recommend", json=BODY).json()
    assert data["status"] == "SUCCESS" and data["recommendation"]["meal_id"] == 1 and data["llm_calls"] == 2
    assert [e["type"] for e in backend.traces[0]["events"]] == ["run_start", "llm_call", "tool_call", "llm_call", "verify", "final"]
