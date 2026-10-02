import logging

import openai
from fastapi import Depends, FastAPI, HTTPException
from openai import AsyncOpenAI

from app import llm
from app.config import Settings, get_settings

logger = logging.getLogger("ai-service")

app = FastAPI(title="MealMind AI Service")


def get_client(settings: Settings = Depends(get_settings)) -> AsyncOpenAI:
    try:
        return llm.make_client(settings)
    except llm.LlmNotConfigured:
        raise HTTPException(status_code=503, detail="OPENAI_API_KEY is not configured")


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/ping")
async def ping(
    client: AsyncOpenAI = Depends(get_client),
    settings: Settings = Depends(get_settings),
) -> dict[str, str]:
    try:
        reply = await llm.ping(client, settings.openai_model)
    except openai.APIStatusError as e:
        # Log only the status code and class; never the request, headers or key.
        logger.warning("OpenAI call failed: %s (status %s)", type(e).__name__, e.status_code)
        raise HTTPException(status_code=502, detail=f"OpenAI returned HTTP {e.status_code}")
    except openai.OpenAIError as e:
        logger.warning("OpenAI call failed: %s", type(e).__name__)
        raise HTTPException(status_code=502, detail="Could not reach OpenAI")
    return {"status": "ok", "model": settings.openai_model, "reply": reply}
