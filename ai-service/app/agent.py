"""The hand-written agent loop: call the model, run the tools it asks for, hand the results back, repeat.

    model call -> (tool requests?) -> run every requested tool -> append one role=tool message per request -> model call ...
    -> final answer (JSON) -> validate it -> verify it in code -> done, or tell the model what is wrong and go again

Every step is bounded: at most `max_rounds` model calls in total (retries for malformed or unverified answers also
count), and at most `max_format_retries` / `max_verify_retries` corrections. Two guards keep a stuck model cheap:
an exact repeat of an earlier tool call is not executed again (the model is told so), and tools are switched off
(tool_choice="none") for the last rounds and as soon as a hard-constraints-only search has found nothing, so the
model must answer instead of searching on. Nothing in here raises on bad model output
or a failing tool; it ends the run with a status instead.
"""

import json
import time
import uuid
from dataclasses import dataclass, field
from enum import Enum
from typing import Any

import openai
from pydantic import ValidationError

from app.backend import ToolError
from app.schemas import Recommendation
from app.spend import BudgetExceeded, SpendTracker, cost_usd
from app.tools import AgentTools, ToolOutcome


class Status(str, Enum):
    SUCCESS = "SUCCESS"            # a valid answer that passed verification
    UNVERIFIED = "UNVERIFIED"      # a valid answer that still failed verification after the allowed corrections; do not show it
    PARSE_FAILED = "PARSE_FAILED"  # the model never produced a valid JSON answer; raw_final keeps the last attempt
    MAX_ROUNDS = "MAX_ROUNDS"      # ran out of model calls
    ERROR = "ERROR"                # the LLM call itself failed (network, auth, budget cap, ...)
    NEEDS_INPUT = "NEEDS_INPUT"    # paused: the run is waiting for the user to answer a question (LangGraph engine only)


@dataclass
class Usage:
    llm_calls: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0
    cost_usd: float = 0.0


@dataclass
class AgentRun:
    trace_id: str
    status: Status
    recommendation: Recommendation | None
    raw_final: str | None
    rounds: int
    events: list[dict[str, Any]]
    usage: Usage
    model: str
    prompt_version: str
    duration_ms: int
    verification: dict[str, Any] | None = None  # the last verification report
    error: str | None = None
    question: dict[str, Any] | None = None      # set when status is NEEDS_INPUT

    def trace_payload(self, session_id: str, user_id: int) -> dict[str, Any]:
        """Body for POST /internal/v1/traces (see notes/agent/internal-api.md)."""
        return {"traceId": self.trace_id, "sessionId": session_id, "userId": user_id, "status": self.status.value,
                "durationMs": self.duration_ms, "errorMessage": self.error, "events": self.events}


MAX_RAW_FINAL_CHARS = 4000  # keeps one trace row small; the parsed recommendation is stored separately and in full


def _snapshot(meal: Any) -> dict[str, Any]:
    """The facts about a candidate as the agent saw them (null = unknown)."""
    return {"id": meal.id, "name": meal.name, "price": meal.price, "protein_g": meal.protein_g,
            "calories": meal.calories, "allergens": meal.allergens}


def response_format() -> dict[str, Any]:
    """Strict JSON-schema response format derived from the Recommendation model (applies to non-tool replies)."""
    schema = openai.pydantic_function_tool(Recommendation, name="recommendation")["function"]["parameters"]
    return {"type": "json_schema", "json_schema": {"name": "recommendation", "strict": True, "schema": schema}}


_FORCED_NOTES = {
    "last_round": ("You have no tool calls left. Answer now with the best result you have, "
                   "or with meal_id null if nothing fits."),
    "hard_constraints_unsatisfiable": ("A search using only the hard constraints found no meal, so searching further cannot help. "
                                       "Answer now with meal_id null and name the constraint that ruled everything out."),
}


def _forced_final_reason(tools: AgentTools, round_no: int, max_rounds: int) -> str | None:
    if tools.context.empty_hard_searches:
        return "hard_constraints_unsatisfiable"
    # Switch tools off early enough that a verification correction still has rounds left (two, for max_rounds >= 5).
    force_from = max_rounds - 2 if max_rounds >= 5 else max_rounds
    if max_rounds > 1 and round_no >= force_from:
        return "last_round"
    return None


def _loads_or_raw(text: str | None) -> Any:
    try:
        return json.loads(text) if text else {}
    except json.JSONDecodeError:
        return text


def _issue_lines(report: dict[str, Any]) -> str:
    return "; ".join(f"[{i['severity']}] {i['code']}: {i['message']}" for i in report.get("issues", []))


def final_event(status: Status, rounds: int, usage: Usage, tools: AgentTools, recommendation: Recommendation | None,
                raw: str | None, error: str | None) -> dict[str, Any]:
    """The closing "snapshot" event. The data behind the answer may change in the database later, and the model cannot
    be re-run to reproduce it, so what was seen and decided is recorded here. Shared by every agent engine."""
    return {
        "type": "final", "round": rounds, "status": status.value,
        "usage": {"llm_calls": usage.llm_calls, "prompt_tokens": usage.prompt_tokens,
                  "completion_tokens": usage.completion_tokens, "cost_usd": round(usage.cost_usd, 6)},
        "constraints": tools.context.constraints().model_dump(),
        "candidates_seen": [_snapshot(m) for m in tools.context.candidates.values()],
        "recommendation": recommendation.model_dump(mode="json") if recommendation is not None else None,
        "raw_final": (raw or "")[:MAX_RAW_FINAL_CHARS] or None,
        "error": error,
    }


async def run_agent(
    client: Any,
    tools: AgentTools,
    user_message: str,
    *,
    model: str,
    system_prompt: str,
    prompt_version: str = "v1",
    temperature: float = 0.0,
    max_rounds: int = 8,
    max_format_retries: int = 2,
    max_verify_retries: int = 2,
    spend: SpendTracker | None = None,
    trace_id: str | None = None,
) -> AgentRun:
    started = time.perf_counter()
    events: list[dict[str, Any]] = []
    usage = Usage()
    state: dict[str, Any] = {"rec": None, "raw": None, "report": None, "error": None}

    events.append({"type": "run_start", "model": model, "temperature": temperature, "prompt_version": prompt_version,
                   "max_rounds": max_rounds, "max_format_retries": max_format_retries,
                   "max_verify_retries": max_verify_retries, "user_message": user_message})

    def finish(status: Status, rounds: int, recommendation: Recommendation | None = None) -> AgentRun:
        events.append(final_event(status, rounds, usage, tools, recommendation, state["raw"], state["error"]))
        return AgentRun(trace_id=trace_id or f"run_{uuid.uuid4().hex}", status=status, recommendation=recommendation,
                        raw_final=state["raw"], rounds=rounds, events=events, usage=usage, model=model,
                        prompt_version=prompt_version, duration_ms=int((time.perf_counter() - started) * 1000),
                        verification=state["report"], error=state["error"])

    try:
        definitions = await tools.definitions()
    except ToolError as e:
        state["error"] = f"could not load tool definitions: {e}"
        return finish(Status.ERROR, 0)
    fmt = response_format()
    messages: list[dict[str, Any]] = [{"role": "system", "content": system_prompt},
                                      {"role": "user", "content": user_message}]
    format_retries = verify_retries = 0
    seen_calls: dict[tuple[str, str], str] = {}  # (tool, normalized arguments) -> summary of the first result

    for round_no in range(1, max_rounds + 1):
        call_started = time.perf_counter()
        try:
            if spend is not None:
                spend.check()
            kwargs: dict[str, Any] = {}
            call_messages = messages
            forced = _forced_final_reason(tools, round_no, max_rounds)
            if forced is not None:
                # Forbid tools so the model has to answer (or say nothing fits) instead of searching on.
                kwargs["tool_choice"] = "none"
                call_messages = messages + [{"role": "user", "content": _FORCED_NOTES[forced]}]
                events.append({"type": "forced_final", "round": round_no, "reason": forced})
            response = await client.chat.completions.create(
                model=model, temperature=temperature, messages=call_messages, tools=definitions,
                response_format=fmt, **kwargs)
        except BudgetExceeded as e:
            state["error"] = str(e)
            return finish(Status.ERROR, round_no - 1)
        except openai.OpenAIError as e:
            code = getattr(e, "status_code", None)
            state["error"] = f"LLM call failed: {type(e).__name__}" + (f" (HTTP {code})" if code else "")
            return finish(Status.ERROR, round_no)

        message = response.choices[0].message
        tool_calls = list(message.tool_calls or [])
        usage.llm_calls += 1
        if getattr(response, "usage", None) is not None:
            usage.prompt_tokens += response.usage.prompt_tokens
            usage.completion_tokens += response.usage.completion_tokens
            usage.cost_usd += (spend.add(model, response.usage.prompt_tokens, response.usage.completion_tokens)
                               if spend is not None
                               else cost_usd(model, response.usage.prompt_tokens, response.usage.completion_tokens))
        events.append({"type": "llm_call", "round": round_no, "finish_reason": response.choices[0].finish_reason,
                       "prompt_tokens": getattr(response.usage, "prompt_tokens", None),
                       "completion_tokens": getattr(response.usage, "completion_tokens", None),
                       "duration_ms": int((time.perf_counter() - call_started) * 1000),
                       "tool_calls": [c.function.name for c in tool_calls]})

        if tool_calls:
            messages.append({"role": "assistant", "content": message.content, "tool_calls": [
                {"id": c.id, "type": "function", "function": {"name": c.function.name, "arguments": c.function.arguments}}
                for c in tool_calls]})
            # Every request gets its own role=tool message (matched by id), and all of them are in place
            # before the model is called again; a missing result would make the next call fail.
            for call in tool_calls:
                key = (call.function.name, json.dumps(_loads_or_raw(call.function.arguments), sort_keys=True))
                if key in seen_calls:
                    outcome = ToolOutcome(call.function.name, json.dumps({"error": (
                        f"duplicate call: you already made exactly this call and it returned {seen_calls[key]}. "
                        "Repeating it will not change the result; use what you have or finish.")}),
                        True, 0, "duplicate call skipped")
                else:
                    outcome = await tools.execute(call.function.name, call.function.arguments)
                    seen_calls[key] = outcome.summary
                events.append({"type": "tool_call", "round": round_no, "tool": call.function.name,
                               "arguments": _loads_or_raw(call.function.arguments), "is_error": outcome.is_error,
                               "summary": outcome.summary, "duration_ms": outcome.duration_ms})
                messages.append({"role": "tool", "tool_call_id": call.id, "content": outcome.content})
            continue

        # A reply without tool requests is the final answer.
        content = message.content or ""
        state["raw"] = content
        try:
            recommendation = Recommendation.model_validate_json(content)
        except ValidationError as e:
            problems = "; ".join(f"{'.'.join(str(p) for p in err['loc']) or 'answer'}: {err['msg']}" for err in e.errors())
            events.append({"type": "format_retry" if format_retries < max_format_retries else "format_failed",
                           "round": round_no, "errors": problems})
            if format_retries >= max_format_retries or round_no == max_rounds:
                state["error"] = f"final answer was not valid (corrections used: {format_retries}): {problems}"
                return finish(Status.PARSE_FAILED, round_no)
            format_retries += 1
            messages += [{"role": "assistant", "content": content},
                         {"role": "user", "content": f"Your answer was not valid: {problems}. "
                                                     "Reply again with ONLY the corrected JSON object."}]
            continue

        # Verification is enforced here in code, whether or not the model called the verify tool itself.
        outcome = await tools.execute("verify_recommendation", json.dumps({"recommendation": recommendation.model_dump(mode="json")}))
        report = json.loads(outcome.content)
        state["report"] = report
        events.append({"type": "verify", "round": round_no, "ok": bool(report.get("ok")),
                       "issues": [i["code"] for i in report.get("issues", [])]})
        if outcome.is_error:
            state["error"] = f"verification could not run: {report.get('error')}"
            return finish(Status.ERROR, round_no)
        if report["ok"]:
            return finish(Status.SUCCESS, round_no, recommendation)
        if verify_retries >= max_verify_retries or round_no == max_rounds:
            state["error"] = f"answer still failed verification: {_issue_lines(report)}"
            return finish(Status.UNVERIFIED, round_no, recommendation)
        verify_retries += 1
        messages += [{"role": "assistant", "content": content},
                     {"role": "user", "content": f"Verification failed: {_issue_lines(report)}. Fix these problems, choose "
                                                 "another meal, or answer with meal_id null, and reply with ONLY the JSON object."}]

    state["error"] = f"no final answer within {max_rounds} model calls"
    return finish(Status.MAX_ROUNDS, max_rounds)


def format_timeline(run: AgentRun) -> str:
    """Human-readable per-round timeline (round / what was called / result), used by tests, demos and docs."""
    lines = [f"run {run.trace_id}  model={run.model}  prompt={run.prompt_version}"]
    for e in run.events:
        if e["type"] == "run_start":
            continue
        r = f"R{e['round']}"
        if e["type"] == "llm_call":
            calls = ", ".join(e["tool_calls"]) or "final answer"
            lines.append(f"{r}  model -> {calls}  [{e['prompt_tokens']} in / {e['completion_tokens']} out, {e['duration_ms']} ms]")
        elif e["type"] == "tool_call":
            args = json.dumps(e["arguments"]) if not isinstance(e["arguments"], str) else e["arguments"]
            lines.append(f"{r}    tool {e['tool']}({args[:90]}) -> {'ERROR ' if e['is_error'] else ''}{e['summary']}")
        elif e["type"] in ("format_retry", "format_failed"):
            lines.append(f"{r}  {e['type']}: {e['errors'][:100]}")
        elif e["type"] == "forced_final":
            lines.append(f"{r}  tools disabled: {e['reason']}")
        elif e["type"] == "verify":
            lines.append(f"{r}  verify -> {'ok' if e['ok'] else 'FAIL ' + ', '.join(e['issues'])}")
        elif e["type"] == "run_start":
            continue
        elif e["type"] == "final":
            lines.append(f"{r}  == {e['status']} ==")
    lines.append(f"total: {run.rounds} rounds, {run.usage.prompt_tokens} in / {run.usage.completion_tokens} out tokens, "
                 f"${run.usage.cost_usd:.5f}, {run.duration_ms} ms")
    return "\n".join(lines)
