from types import SimpleNamespace

import httpx
import openai
import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app import llm
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
    if hasattr(app.state, "llm_client"):
        del app.state.llm_client


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


@pytest.mark.parametrize("blank", ["", "   "])
def test_ping_with_blank_key_returns_503_not_500(blank):
    # A blank key used to slip past the "is None" check and crash inside the SDK with a 500 + traceback.
    app.dependency_overrides[get_settings] = lambda: Settings(_env_file=None, openai_api_key=SecretStr(blank),
                                                              openai_model="test-model")
    response = TestClient(app).post("/ping")
    assert response.status_code == 503
    assert "not configured" in response.json()["detail"]


def test_ping_without_model_returns_503_instead_of_guessing_a_model():
    app.dependency_overrides[get_settings] = lambda: Settings(_env_file=None, openai_api_key=SecretStr("sk-test-secret"))
    response = TestClient(app).post("/ping")
    assert response.status_code == 503
    assert "OPENAI_MODEL" in response.json()["detail"]


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


def test_client_is_created_once_and_reused(monkeypatch):
    created = []

    def fake_make_client(settings):
        client = FakeClient("pong")
        created.append(client)
        return client

    monkeypatch.setattr(llm, "make_client", fake_make_client)
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None, openai_api_key=SecretStr("sk-test-secret"), openai_model="m")
    http = TestClient(app)
    http.post("/ping")
    http.post("/ping")
    assert len(created) == 1  # one shared client, not one per request


def test_make_client_sets_timeout_and_retry_limits():
    client = llm.make_client(Settings(_env_file=None, openai_api_key=SecretStr("sk-test-secret"),
                                      llm_timeout_s=7.0, llm_max_retries=1))
    assert client.max_retries == 1
    assert client.timeout == 7.0


def test_settings_hides_key_and_derives_base_url():
    s = Settings(
        _env_file=None,
        openai_api_key=SecretStr("sk-test-secret"),
        llm_api_url="https://example.com/v1/chat/completions",
    )
    assert "sk-test-secret" not in repr(s)
    assert s.openai_base_url == "https://example.com/v1"


def test_settings_ignore_the_shell_environment_in_tests(monkeypatch):
    # conftest's isolated_env removed these; setting one later must still be the only way to configure them.
    monkeypatch.setenv("OPENAI_MODEL", "from-env")
    assert Settings(_env_file=None).openai_model == "from-env"
    monkeypatch.delenv("OPENAI_MODEL")
    assert Settings(_env_file=None).openai_model is None


def test_agent_model_default_is_explicit():
    s = Settings(_env_file=None)
    assert s.agent_model == "gpt-4o-mini"
    assert s.agent_temperature == 0.0
