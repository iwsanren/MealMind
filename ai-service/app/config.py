from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

# ai-service/app/config.py -> MealMind/backend/.env
# Outside Docker this file is found on disk; inside Docker it does not exist
# (see .dockerignore) and the same variables arrive as real env vars instead.
BACKEND_ENV_FILE = Path(__file__).resolve().parents[2] / "backend" / ".env"

# Single source of truth for the nutrition reference stays in the Java backend's resources.
DEFAULT_NUTRITION_FILE = (
    Path(__file__).resolve().parents[2]
    / "backend" / "src" / "main" / "resources" / "knowledge" / "nutrition_reference_v1.md"
)


# Where paused agent runs are saved (SQLite). Not committed: see .gitignore. In Docker it is a volume (see docker-compose.yml).
DEFAULT_CHECKPOINT_FILE = Path(__file__).resolve().parents[1] / "data" / "checkpoints.sqlite"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=BACKEND_ENV_FILE,
        env_file_encoding="utf-8-sig",  # tolerate a BOM written by Windows editors
        extra="ignore",  # backend/.env also holds SPRING_* values we don't need
    )

    # SecretStr keeps the key out of repr(), logs and tracebacks.
    openai_api_key: SecretStr | None = None
    # No silent default: a missing model must be an explicit "not configured", not a surprise gpt-3.5.
    openai_model: str | None = None
    # The Java backend stores the full chat-completions URL here.
    llm_api_url: str | None = None

    # Agent runs. The model is an explicit, documented default (recorded in every trace), overridable via AGENT_MODEL.
    agent_model: str = "gpt-4o-mini"
    agent_temperature: float = 0.0
    agent_prompt_version: str = "v5"
    # Which loop runs the agent: the hand-written one (app/agent.py) or the LangGraph one (app/agent_graph.py). Same behaviour,
    # same tests; the default stays the hand-written loop.
    agent_engine: Literal["handwritten", "langgraph"] = "handwritten"
    agent_checkpoint_db: Path = DEFAULT_CHECKPOINT_FILE
    agent_max_rounds: int = 8
    agent_max_format_retries: int = 2
    agent_max_verify_retries: int = 2
    llm_timeout_s: float = 30.0
    llm_max_retries: int = 2
    # Per-request guards for POST /v1/recommend: a wall-clock deadline (the Java caller times out a little later) and a
    # spend cap, so one stuck or looping request cannot run up a bill.
    agent_deadline_s: float = 30.0
    agent_request_cap_usd: float = 0.05

    # Java backend (internal API for tools).
    internal_api_base_url: str = "http://localhost:8080"
    backend_timeout_s: float = 10.0

    nutrition_reference_file: Path = DEFAULT_NUTRITION_FILE

    @property
    def openai_base_url(self) -> str | None:
        """Convert the backend's full endpoint URL into the SDK's base_url."""
        if not self.llm_api_url:
            return None
        return self.llm_api_url.removesuffix("/chat/completions").rstrip("/")

    @property
    def has_api_key(self) -> bool:
        return self.openai_api_key is not None and bool(self.openai_api_key.get_secret_value().strip())


@lru_cache
def get_settings() -> Settings:
    return Settings()
