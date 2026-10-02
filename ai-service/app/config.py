from functools import lru_cache
from pathlib import Path

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

# ai-service/app/config.py -> MealMind/backend/.env
# Outside Docker this file is found on disk; inside Docker it does not exist
# (see .dockerignore) and the same variables arrive as real env vars instead.
BACKEND_ENV_FILE = Path(__file__).resolve().parents[2] / "backend" / ".env"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=BACKEND_ENV_FILE,
        env_file_encoding="utf-8-sig",  # tolerate a BOM written by Windows editors
        extra="ignore",  # backend/.env also holds SPRING_* values we don't need
    )

    # SecretStr keeps the key out of repr(), logs and tracebacks.
    openai_api_key: SecretStr | None = None
    openai_model: str = "gpt-3.5-turbo"
    # The Java backend stores the full chat-completions URL here.
    llm_api_url: str | None = None

    @property
    def openai_base_url(self) -> str | None:
        """Convert the backend's full endpoint URL into the SDK's base_url."""
        if not self.llm_api_url:
            return None
        return self.llm_api_url.removesuffix("/chat/completions").rstrip("/")


@lru_cache
def get_settings() -> Settings:
    return Settings()
