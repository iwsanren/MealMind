import openai
from openai import AsyncOpenAI

from app.config import Settings


class LlmNotConfigured(Exception):
    """The API key is missing or blank, or the SDK refused to build a client."""


def make_client(settings: Settings) -> AsyncOpenAI:
    # A blank key ("") is as unusable as a missing one; the SDK would raise its own error for it.
    if not settings.has_api_key:
        raise LlmNotConfigured
    try:
        return AsyncOpenAI(
            api_key=settings.openai_api_key.get_secret_value(),
            base_url=settings.openai_base_url,
            timeout=settings.llm_timeout_s,
            max_retries=settings.llm_max_retries,
        )
    except openai.OpenAIError as e:
        raise LlmNotConfigured from e


async def ping(client: AsyncOpenAI, model: str) -> str:
    """Smallest possible round trip: proves the key, model and URL all work."""
    response = await client.chat.completions.create(
        model=model,
        messages=[{"role": "user", "content": "Repeat this word and nothing else: pong"}],
    )
    return (response.choices[0].message.content or "").strip()
