"""One stateless agent run for the Java backend (POST /v1/recommend).

The Java side owns the session: it sends the user's words, the slots collected so far, the source mode, the user id and
the meals to skip. This module runs the agent once, records the trace, and returns the outcome. Anything other than a
verified answer is reported as a status, never as an exception, so the caller can decide to fall back to its rules.
"""

import asyncio
import logging
import uuid
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.agent import AgentRun, Status, run_agent
from app.agent_graph import read_paused, resume_agent_graph, run_agent_graph
from app.audit import record_run
from app.config import Settings
from app.nutrition import NutritionReference
from app.prompts import load_agent_prompt
from app.schemas import Recommendation
from app.spend import SpendTracker
from app.tools import AgentTools, RunContext

logger = logging.getLogger("ai-service.recommend")

SLOT_DIMENSIONS = ("mealTime", "mood", "scene", "healthGoal", "cuisine", "taste", "convenience")


class RecommendRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    user_message: str = Field(min_length=1, max_length=1000, description="The user's own words for this turn.")
    session_id: str = Field(min_length=1, max_length=64)
    user_id: int = Field(ge=1)
    source_mode: Literal["PUBLIC", "PERSONAL"]
    slots: dict[str, list[str]] = Field(default_factory=dict, description="Soft preferences gathered earlier in the chat.")
    exclude_meal_ids: list[int] = Field(default_factory=list, max_length=100)
    allow_questions: bool = Field(default=False, description="Let the agent pause and ask the user a question "
                                                              "(needs AGENT_ENGINE=langgraph).")

    @field_validator("slots")
    @classmethod
    def _known_dimensions(cls, slots: dict[str, list[str]]) -> dict[str, list[str]]:
        unknown = set(slots) - set(SLOT_DIMENSIONS)
        if unknown:
            raise ValueError(f"unknown slot dimensions: {sorted(unknown)}")
        if any(len(tag) > 60 for tags in slots.values() for tag in tags) or any(len(tags) > 10 for tags in slots.values()):
            raise ValueError("slot values too long")
        return slots


class ResumeRequest(BaseModel):
    """The person's answer to a question the agent paused on."""

    model_config = ConfigDict(extra="forbid")

    user_id: int = Field(ge=1, description="Must be the user the run belongs to.")
    decision: Literal["raise_budget", "decline"]
    max_price: float | None = Field(default=None, gt=0, le=1000, description="Required for raise_budget.")
    note: str | None = Field(default=None, max_length=300, description="The person's comment, passed on to the model.")

    @model_validator(mode="after")
    def _raise_needs_a_number(self) -> "ResumeRequest":
        if self.decision == "raise_budget" and self.max_price is None:
            raise ValueError("max_price is required for raise_budget")
        return self


class RecommendResponse(BaseModel):
    trace_id: str
    status: Status
    recommendation: Recommendation | None = None  # present only when the answer passed verification
    thread_id: str | None = None                  # NEEDS_INPUT only: the id to resume with (equals trace_id)
    question: dict[str, Any] | None = None        # NEEDS_INPUT only
    trace_written: bool = False
    error: str | None = None
    model: str
    prompt_version: str
    rounds: int = 0
    llm_calls: int = 0
    cost_usd: float = 0.0
    duration_ms: int = 0


def compose_user_message(request: RecommendRequest) -> str:
    """The user's words first and unchanged; earlier slots are added only as soft context, clearly labelled."""
    text = request.user_message.strip()
    known = [f"{dim}: {', '.join(tags)}" for dim in SLOT_DIMENSIONS if (tags := request.slots.get(dim))]
    if not known:
        return text
    return (f"{text}\n\n(Soft preferences already known from earlier in this chat; what the user says above takes "
            f"precedence. {'; '.join(known)})")


def _response(run: AgentRun, written: bool) -> RecommendResponse:
    # Only a verified answer is handed out. UNVERIFIED / PARSE_FAILED / MAX_ROUNDS carry no recommendation on purpose.
    shown = run.recommendation if run.status is Status.SUCCESS else None
    paused = run.status is Status.NEEDS_INPUT
    return RecommendResponse(
        trace_id=run.trace_id, status=run.status, recommendation=shown, trace_written=written, error=run.error,
        thread_id=run.trace_id if paused else None, question=run.question,
        model=run.model, prompt_version=run.prompt_version, rounds=run.rounds, llm_calls=run.usage.llm_calls,
        cost_usd=round(run.usage.cost_usd, 6), duration_ms=run.duration_ms)


def _agent_kwargs(settings: Settings) -> dict[str, Any]:
    return dict(model=settings.agent_model, temperature=settings.agent_temperature,
                prompt_version=settings.agent_prompt_version, max_rounds=settings.agent_max_rounds,
                max_format_retries=settings.agent_max_format_retries, max_verify_retries=settings.agent_max_verify_retries,
                spend=SpendTracker(cap_usd=settings.agent_request_cap_usd))


async def run_recommendation(request: RecommendRequest, *, client: Any, backend: Any, nutrition: NutritionReference,
                             settings: Settings, checkpointer: Any = None) -> RecommendResponse:
    tools = AgentTools(backend, nutrition, RunContext(user_id=request.user_id, source_mode=request.source_mode,
                                                      exclude_meal_ids=list(request.exclude_meal_ids)))
    trace_id = f"run_{uuid.uuid4().hex}"
    try:
        if request.allow_questions:
            run = await asyncio.wait_for(
                run_agent_graph(client, tools, compose_user_message(request),
                                system_prompt=load_agent_prompt(settings.agent_prompt_version), trace_id=trace_id,
                                checkpointer=checkpointer, allow_questions=True,
                                meta={"session_id": request.session_id, "user_id": request.user_id}, **_agent_kwargs(settings)),
                timeout=settings.agent_deadline_s)
        else:
            engine = run_agent_graph if settings.agent_engine == "langgraph" else run_agent
            run = await asyncio.wait_for(
                engine(client, tools, compose_user_message(request), system_prompt=load_agent_prompt(settings.agent_prompt_version),
                       trace_id=trace_id, **_agent_kwargs(settings)),
                timeout=settings.agent_deadline_s)
    except asyncio.TimeoutError:
        logger.warning("agent run %s exceeded the %.0fs deadline", trace_id, settings.agent_deadline_s)
        return RecommendResponse(trace_id=trace_id, status=Status.ERROR, error="agent deadline exceeded",
                                 model=settings.agent_model, prompt_version=settings.agent_prompt_version)
    if run.status is Status.NEEDS_INPUT:      # not finished: the trace is stored when the run ends
        return _response(run, False)
    outcome = await record_run(backend, run, session_id=request.session_id, user_id=request.user_id)
    return _response(run, outcome.written)


async def resume_recommendation(thread_id: str, request: ResumeRequest, *, client: Any, backend: Any,
                                nutrition: NutritionReference, settings: Settings, checkpointer: Any) -> RecommendResponse:
    """Continue a paused run with the person's answer. Raises RunNotFound / RunFinished (the endpoint maps them to
    404 / 409); an answer that cannot be right for the question raises ValueError (422)."""
    saved = await read_paused(checkpointer, thread_id, user_id=request.user_id)
    if request.decision == "raise_budget":
        current = saved["tool_context"]["empty_hard_searches"][-1][0]
        if request.max_price is not None and current is not None and request.max_price <= current:
            raise ValueError(f"max_price must be higher than the current budget (€{current:g})")
    tools = AgentTools(backend, nutrition, RunContext(user_id=request.user_id))   # filled from the saved state
    decision = {"decision": request.decision, "max_price": request.max_price, "note": request.note}
    try:
        run = await asyncio.wait_for(
            resume_agent_graph(client, tools, thread_id=thread_id, decision=decision, checkpointer=checkpointer,
                               user_id=request.user_id, **_agent_kwargs(settings)),
            timeout=settings.agent_deadline_s)
    except asyncio.TimeoutError:
        logger.warning("resumed agent run %s exceeded the %.0fs deadline", thread_id, settings.agent_deadline_s)
        return RecommendResponse(trace_id=thread_id, status=Status.ERROR, error="agent deadline exceeded",
                                 model=settings.agent_model, prompt_version=settings.agent_prompt_version)
    if run.status is Status.NEEDS_INPUT:
        return _response(run, False)
    outcome = await record_run(backend, run, session_id=saved["meta"]["session_id"], user_id=request.user_id)
    return _response(run, outcome.written)
