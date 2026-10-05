import json

import httpx
import pytest

from app.backend import BackendClient, ToolError
from tests.conftest import run


def client_with(handler) -> BackendClient:
    return BackendClient("http://backend.test", transport=httpx.MockTransport(handler))


def test_search_posts_the_body_and_returns_json():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["path"], seen["body"] = request.url.path, json.loads(request.content)
        return httpx.Response(200, json={"count": 0, "meals": []})

    result = run(client_with(handler).search_meals({"sourceMode": "PUBLIC", "maxPrice": 15}))

    assert result == {"count": 0, "meals": []}
    assert seen == {"path": "/internal/v1/meals/search", "body": {"sourceMode": "PUBLIC", "maxPrice": 15}}


def test_recent_feedback_uses_the_explicit_user_id_and_limit():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        return httpx.Response(200, json={"userId": 7, "count": 0, "items": []})

    run(client_with(handler).recent_feedback(7, 5))
    assert seen["url"] == "http://backend.test/internal/v1/users/7/recent-feedback?limit=5"


def test_4xx_becomes_a_tool_error_with_the_backend_message():
    def handler(request):
        return httpx.Response(400, json={"message": "Unknown allergen 'x'"})

    with pytest.raises(ToolError, match="HTTP 400: Unknown allergen 'x'"):
        run(client_with(handler).search_meals({}))


def test_5xx_with_a_non_json_body_is_still_a_tool_error():
    with pytest.raises(ToolError, match="HTTP 500"):
        run(client_with(lambda r: httpx.Response(500, text="<html>boom</html>")).risk_check("x"))


def test_unreachable_backend_is_a_tool_error_that_does_not_leak_the_url():
    def handler(request):
        raise httpx.ConnectError("connection refused to http://backend.test/internal/v1/risk/check")

    with pytest.raises(ToolError) as excinfo:
        run(client_with(handler).risk_check("x"))
    assert "ConnectError" in str(excinfo.value)
    assert "backend.test" not in str(excinfo.value)


def test_slot_options_are_fetched_once():
    calls = []

    def handler(request):
        calls.append(request.url.path)
        return httpx.Response(200, json={"mealTime": ["Lunch"]})

    async def twice():
        c = client_with(handler)
        return await c.slot_options(), await c.slot_options()

    first, second = run(twice())
    assert first == second == {"mealTime": ["Lunch"]}
    assert calls == ["/api/v1/slot-options"]
