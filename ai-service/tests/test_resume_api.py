"""POST /v1/recommend (allow_questions) and POST /v1/recommend/{thread_id}/resume, with a scripted fake model."""

import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.config import Settings, get_settings
from app.main import app, get_backend, get_client, get_nutrition
from tests.test_agent import NO_MATCH, NUTRITION, FakeLLM, final_reply, recommendation, tool_reply

BODY = {"user_message": "dinner under $3, allergic to shellfish", "session_id": "sess_1", "user_id": 7,
        "source_mode": "PUBLIC", "allow_questions": True}
TOO_CHEAP = ("search_meals", {"max_price": 3, "exclude_allergens": ["shellfish"]})
RAISE = {"user_id": 7, "decision": "raise_budget", "max_price": 20}


@pytest.fixture
def http(tmp_path, backend):
    """One client = one event loop for the whole test (the SQLite connection lives in it), lifespan included."""
    llm = FakeLLM()
    state = {"llm": llm}
    app.dependency_overrides[get_client] = lambda: state["llm"]
    app.dependency_overrides[get_backend] = lambda: backend
    app.dependency_overrides[get_nutrition] = lambda: NUTRITION
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None, openai_api_key=SecretStr("sk-test"), agent_engine="langgraph",
        agent_checkpoint_db=tmp_path / "data" / "checkpoints.sqlite")
    with TestClient(app) as client:
        client.state = state
        yield client
    app.dependency_overrides.clear()
    for name in ("checkpointer", "checkpointer_stack"):
        if hasattr(app.state, name):
            delattr(app.state, name)


def script(http, *items):
    http.state["llm"].script[:] = list(items)


def pause(http):
    script(http, tool_reply(TOO_CHEAP))
    response = http.post("/v1/recommend", json=BODY)
    assert response.status_code == 200
    return response.json()


def test_a_run_that_needs_the_user_returns_a_question_and_a_thread_id_and_stores_no_trace_yet(http, backend):
    data = pause(http)

    assert data["status"] == "NEEDS_INPUT" and data["recommendation"] is None
    assert data["thread_id"] == data["trace_id"] and data["trace_written"] is False
    assert data["question"]["type"] == "relax_budget" and data["question"]["allergens_can_be_relaxed"] is False
    assert backend.traces == []


def test_resuming_with_a_higher_budget_finishes_the_run_and_stores_one_trace(http, backend):
    first = pause(http)
    script(http, tool_reply(("search_meals", {"max_price": 20, "exclude_allergens": ["shellfish"]})),
           final_reply(recommendation(meal_id=1, price="12.5")))

    response = http.post(f"/v1/recommend/{first['thread_id']}/resume", json=RAISE)

    data = response.json()
    assert response.status_code == 200 and data["status"] == "SUCCESS" and data["recommendation"]["meal_id"] == 1
    assert data["trace_id"] == first["trace_id"] and data["trace_written"] is True
    assert len(backend.traces) == 1
    trace = backend.traces[0]
    assert trace["sessionId"] == "sess_1" and trace["userId"] == 7
    assert [e["type"] for e in trace["events"]][3:5] == ["interrupt", "resume"]


def test_declining_with_a_comment_ends_with_a_no_match_answer(http):
    first = pause(http)
    script(http, final_reply(NO_MATCH))

    response = http.post(f"/v1/recommend/{first['thread_id']}/resume",
                         json={"user_id": 7, "decision": "decline", "note": "never mind"})

    assert response.status_code == 200
    assert response.json()["status"] == "SUCCESS" and response.json()["recommendation"]["meal_id"] is None


def test_an_unknown_thread_is_a_404(http):
    response = http.post("/v1/recommend/run_nope/resume", json=RAISE)
    assert response.status_code == 404 and "no paused run" in response.json()["detail"]


def test_another_users_thread_looks_the_same_as_an_unknown_one(http):
    first = pause(http)
    response = http.post(f"/v1/recommend/{first['thread_id']}/resume", json={**RAISE, "user_id": 8})
    assert response.status_code == 404


def test_a_finished_run_cannot_be_resumed_again(http):
    first = pause(http)
    script(http, final_reply(NO_MATCH))
    http.post(f"/v1/recommend/{first['thread_id']}/resume", json={"user_id": 7, "decision": "decline"})

    response = http.post(f"/v1/recommend/{first['thread_id']}/resume", json=RAISE)
    assert response.status_code == 409


@pytest.mark.parametrize("change,expected", [
    ({"max_price": 3}, 422), ({"max_price": 2}, 422), ({"max_price": 5000}, 422), ({"max_price": None}, 422),
    ({"decision": "allergens_off"}, 422), ({"user_id": 0}, 422), ({"extra": 1}, 422),
])
def test_an_answer_that_cannot_be_right_is_rejected_and_the_run_stays_paused(http, change, expected):
    first = pause(http)
    response = http.post(f"/v1/recommend/{first['thread_id']}/resume", json={**RAISE, **change})
    assert response.status_code == expected

    script(http, final_reply(NO_MATCH))           # still resumable afterwards
    ok = http.post(f"/v1/recommend/{first['thread_id']}/resume", json={"user_id": 7, "decision": "decline"})
    assert ok.status_code == 200


def test_questions_need_the_langgraph_engine(backend):
    app.dependency_overrides[get_client] = lambda: FakeLLM()
    app.dependency_overrides[get_backend] = lambda: backend
    app.dependency_overrides[get_nutrition] = lambda: NUTRITION
    app.dependency_overrides[get_settings] = lambda: Settings(_env_file=None, openai_api_key=SecretStr("sk-test"))
    try:
        client = TestClient(app)
        assert client.post("/v1/recommend", json=BODY).status_code == 400
        assert client.post("/v1/recommend/run_x/resume", json=RAISE).status_code == 400
    finally:
        app.dependency_overrides.clear()


def test_without_allow_questions_nothing_changes_for_the_caller(http, backend):
    script(http, tool_reply(TOO_CHEAP), final_reply(NO_MATCH))
    response = http.post("/v1/recommend", json={**BODY, "allow_questions": False})

    assert response.json()["status"] == "SUCCESS" and response.json()["thread_id"] is None
    assert len(backend.traces) == 1
