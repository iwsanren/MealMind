import logging
from contextlib import AsyncExitStack, asynccontextmanager

import openai
from fastapi import Depends, FastAPI, HTTPException, Request
from openai import AsyncOpenAI

from app import llm
from app.backend import BackendClient
from app.config import Settings, get_settings
from app.nutrition import NutritionReference
from app.agent_graph import RunFinished, RunNotFound
from app.recommend import (RecommendRequest, RecommendResponse, ResumeRequest, resume_recommendation,
                           run_recommendation)

logger = logging.getLogger("ai-service")


@asynccontextmanager
async def lifespan(app: FastAPI):
    yield
    # One shared client per process (connection pool), closed on shutdown instead of leaked per request.
    client = getattr(app.state, "llm_client", None)
    if client is not None:
        await client.close()
    backend = getattr(app.state, "backend_client", None)
    if backend is not None:
        await backend.aclose()
    stack = getattr(app.state, "checkpointer_stack", None)
    if stack is not None:
        await stack.aclose()


app = FastAPI(title="MealMind AI Service", lifespan=lifespan)


def get_client(request: Request, settings: Settings = Depends(get_settings)) -> AsyncOpenAI:
    client = getattr(request.app.state, "llm_client", None)
    if client is None:
        try:
            client = llm.make_client(settings)
        except llm.LlmNotConfigured:
            raise HTTPException(status_code=503, detail="OPENAI_API_KEY is not configured")
        request.app.state.llm_client = client
    return client


def get_backend(request: Request, settings: Settings = Depends(get_settings)) -> BackendClient:
    backend = getattr(request.app.state, "backend_client", None)
    if backend is None:
        backend = BackendClient(settings.internal_api_base_url, settings.backend_timeout_s)
        request.app.state.backend_client = backend
    return backend


def get_nutrition(request: Request, settings: Settings = Depends(get_settings)) -> NutritionReference:
    nutrition = getattr(request.app.state, "nutrition", None)
    if nutrition is None:
        nutrition = NutritionReference.from_file(settings.nutrition_reference_file)
        request.app.state.nutrition = nutrition
    return nutrition


async def get_checkpointer(request: Request, settings: Settings = Depends(get_settings)):
    """The SQLite store for paused runs, opened on first use and closed at shutdown."""
    saver = getattr(request.app.state, "checkpointer", None)
    if saver is None:
        from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver
        settings.agent_checkpoint_db.parent.mkdir(parents=True, exist_ok=True)
        stack = AsyncExitStack()
        saver = await stack.enter_async_context(AsyncSqliteSaver.from_conn_string(str(settings.agent_checkpoint_db)))
        request.app.state.checkpointer_stack = stack
        request.app.state.checkpointer = saver
    return saver


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/ping")
async def ping(
    client: AsyncOpenAI = Depends(get_client),
    settings: Settings = Depends(get_settings),
) -> dict[str, str]:
    if not settings.openai_model:
        raise HTTPException(status_code=503, detail="OPENAI_MODEL is not configured")
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


@app.post("/v1/recommend", response_model=RecommendResponse)
async def recommend(
    body: RecommendRequest,
    request: Request,
    client: AsyncOpenAI = Depends(get_client),
    backend: BackendClient = Depends(get_backend),
    nutrition: NutritionReference = Depends(get_nutrition),
    settings: Settings = Depends(get_settings),
) -> RecommendResponse:
    """One agent run. A run that ends without a verified answer is still HTTP 200 with a non-SUCCESS status; HTTP errors
    are reserved for bad requests (400/422) and a missing LLM configuration (503). With allow_questions the run may end
    with status NEEDS_INPUT (a question for the user); continue it with POST /v1/recommend/{thread_id}/resume."""
    checkpointer = None
    if body.allow_questions:
        if settings.agent_engine != "langgraph":
            raise HTTPException(status_code=400, detail="allow_questions needs AGENT_ENGINE=langgraph")
        checkpointer = await get_checkpointer(request, settings)
    return await run_recommendation(body, client=client, backend=backend, nutrition=nutrition, settings=settings,
                                    checkpointer=checkpointer)


@app.post("/v1/recommend/{thread_id}/resume", response_model=RecommendResponse)
async def resume(
    thread_id: str,
    body: ResumeRequest,
    request: Request,
    client: AsyncOpenAI = Depends(get_client),
    backend: BackendClient = Depends(get_backend),
    nutrition: NutritionReference = Depends(get_nutrition),
    settings: Settings = Depends(get_settings),
) -> RecommendResponse:
    """Continue a paused run with the person's answer. 404: no paused run with this id for this user. 409: it already
    finished. 422: the answer cannot be right (e.g. a lower budget). The saved run survives a restart of this service."""
    if settings.agent_engine != "langgraph":
        raise HTTPException(status_code=400, detail="resume needs AGENT_ENGINE=langgraph")
    checkpointer = await get_checkpointer(request, settings)
    try:
        return await resume_recommendation(thread_id, body, client=client, backend=backend, nutrition=nutrition,
                                           settings=settings, checkpointer=checkpointer)
    except RunNotFound:
        raise HTTPException(status_code=404, detail="no paused run with this id")
    except RunFinished:
        raise HTTPException(status_code=409, detail="this run has already finished")
    except ValueError as e:
        raise HTTPException(status_code=422, detail=str(e))
