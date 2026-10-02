from types import SimpleNamespace

import httpx
import openai
import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.config import Settings, get_settings
from app.main import app, get_client


class FakeClient:
    """Stands in for AsyncOpenAI; records the call and returns/raises on demand."""

    def __init__(self, content="pong", error=None):
        self.calls = []
        self._content, self._error = content, error
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self._create))

    async def _create(self, **kwargs):
        self.calls.append(kwargs)
        if self._error:
            raise self._error
        message = SimpleNamespace(content=self._content)
        return SimpleNamespace(choices=[SimpleNamespace(message=message)])


@pytest.fixture(autouse=True)
def reset_overrides():
    yield
    app.dependency_overrides.clear()


def use(client, model="test-model"):
    app.dependency_overrides[get_client] = lambda: client
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None, openai_api_key=SecretStr("sk-test-secret"), openai_model=model
    )


def test_ping_returns_model_reply():
    fake = FakeClient("pong")
    use(fake)
    response = TestClient(app).post("/ping")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "model": "test-model", "reply": "pong"}
    assert fake.calls[0]["model"] == "test-model"


def test_ping_without_key_returns_503():
    app.dependency_overrides[get_settings] = lambda: Settings(_env_file=None)
    response = TestClient(app).post("/ping")
    assert response.status_code == 503


def test_ping_upstream_error_returns_502_without_leaking_key():
    request = httpx.Request("POST", "https://api.openai.com/v1/chat/completions")
    error = openai.AuthenticationError(
        "Incorrect API key provided: sk-test-secret",
        response=httpx.Response(401, request=request),
        body=None,
    )
    use(FakeClient(error=error))
    response = TestClient(app).post("/ping")
    assert response.status_code == 502
    assert "sk-test-secret" not in response.text


def test_settings_hides_key_and_derives_base_url():
    s = Settings(
        _env_file=None,
        openai_api_key=SecretStr("sk-test-secret"),
        llm_api_url="https://example.com/v1/chat/completions",
    )
    assert "sk-test-secret" not in repr(s)
    assert s.openai_base_url == "https://example.com/v1"
