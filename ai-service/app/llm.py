from openai import AsyncOpenAI

from app.config import Settings


class LlmNotConfigured(Exception):
    """OPENAI_API_KEY is missing."""


def make_client(settings: Settings) -> AsyncOpenAI:
    if settings.openai_api_key is None:
        raise LlmNotConfigured
    return AsyncOpenAI(
        api_key=settings.openai_api_key.get_secret_value(),
        base_url=settings.openai_base_url,
    )


async def ping(client: AsyncOpenAI, model: str) -> str:
    """Smallest possible round trip: proves the key, model and URL all work."""
    response = await client.chat.completions.create(
        model=model,
        messages=[{"role": "user", "content": "Repeat this word and nothing else: pong"}],
    )
    return (response.choices[0].message.content or "").strip()
