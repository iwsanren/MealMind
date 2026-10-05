"""The same agent as app/agent.py, with the loop drawn as a LangGraph graph (plus pause-and-ask, plus save/resume).

    START -> call_model --(tool requests)--> run_tools --(nothing fits the hard constraints)--> ask_user -> call_model
                 |                              '--(otherwise)--> call_model
                 |--(correction needed)--> call_model
                 '--(finished: answer, failure or round limit)--> END

What is a graph concept and what is not:
  * state      = AgentState, the one dict every node reads and writes (messages and events are APPENDED to, not replaced).
                 It also carries the tools' per-run context (what the searches returned, the budget in force), so that a
                 run which is saved while paused can be rebuilt in another process.
  * node       = call_model (ask the model; if it answered, validate and verify the answer), run_tools (run the requested
                 tools), ask_user (pause and put a question to the person; see below)
  * edge       = route() / route_after_tools() decide what runs next
  * not graph  = the tools, the verifier, the forced-final rules and the trace events. They are the same plain functions
                 the hand-written loop uses (app.tools, app.verify, app.agent); the framework only runs the loop.

Human in the loop: when a search that used ONLY the hard constraints (budget and allergens) finds nothing and a budget was
set, the run pauses and asks whether the budget may be raised. Allergen limits are never offered for relaxing. The paused
run is saved by the checkpointer under a thread id (the trace id) and continues when resume_agent_graph is called, even in
a new process.

Same contract as run_agent: it returns an AgentRun and never raises on bad model output, a failing tool or a runaway loop.
"""

import json
import operator
import time
import uuid
from typing import Annotated, Any, TypedDict

import openai
from langgraph.errors import GraphRecursionError
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, interrupt
from pydantic import ValidationError

from app.agent import (_FORCED_NOTES, AgentRun, Status, Usage, _forced_final_reason, _issue_lines, _loads_or_raw,
                       final_event, response_format)
from app.backend import ToolError
from app.schemas import Recommendation
from app.spend import BudgetExceeded, SpendTracker, cost_usd
from app.tools import AgentTools, ToolOutcome

MAX_NOTE_CHARS = 300
MAX_BUDGET = 1000.0


class AgentState(TypedDict):
    # operator.add = "append": a node returns only the NEW messages/events and the framework concatenates them.
    # Without a reducer a returned list would REPLACE the history, and the model would lose its conversation.
    messages: Annotated[list[dict[str, Any]], operator.add]
    events: Annotated[list[dict[str, Any]], operator.add]
    round: int                       # model calls made so far
    format_retries: int
    verify_retries: int
    asks: int                        # questions put to the user so far
    pending_calls: list[dict[str, Any]]      # tool requests of the last model reply, not yet run
    seen_calls: dict[str, str]               # "tool|normalized arguments" -> summary of its first result
    tool_context: dict[str, Any]             # RunContext.to_dict(): candidates seen, budget in force, ...
    meta: dict[str, Any]                     # who asked: session_id, user_id (needed to store the trace after a resume)
    llm_calls: int
    prompt_tokens: int
    completion_tokens: int
    cost_usd: float
    active_ms: int                   # time spent working (model + tools); time spent waiting for the user is not counted
    raw: str | None                          # last final-answer text from the model
    report: dict[str, Any] | None            # last verification report
    result: dict[str, Any] | None            # set exactly once, by the node that ends the run; plain JSON-able values only


def _result(status: Status, rounds: int, *, recommendation: Recommendation | None = None, error: str | None = None) -> dict[str, Any]:
    # Plain values (str, dict), not enum/pydantic objects: the state is written to the checkpoint store as it grows.
    return {"status": status.value, "rounds": rounds,
            "recommendation": recommendation.model_dump(mode="json") if recommendation is not None else None, "error": error}


def budget_question(state: AgentState) -> dict[str, Any]:
    """The question put to the person; built only from the state (so building it twice is harmless)."""
    context = state["tool_context"]
    budget = context["empty_hard_searches"][-1][0]
    allergens = context["empty_hard_searches"][-1][1]
    avoiding = f" and avoids {', '.join(allergens)}" if allergens else ""
    return {"type": "relax_budget", "current_max_price": budget, "excluded_allergens": list(allergens),
            "allergens_can_be_relaxed": False,
            "message": f"No meal in the library costs at most ${budget:g}{avoiding}. Do you want to raise the budget? "
                       "Allergen limits stay as they are.",
            "answer_with": {"decision": "raise_budget | decline", "max_price": "number, required for raise_budget", "note": "optional text"}}


def build_agent_graph(client: Any, tools: AgentTools, definitions: list[dict[str, Any]], *, model: str, temperature: float,
                      max_rounds: int, max_format_retries: int, max_verify_retries: int, spend: SpendTracker | None,
                      enforce_round_limit: bool = True, checkpointer: Any = None, allow_questions: bool = False,
                      max_questions: int = 2):
    """enforce_round_limit=False removes our own round limit so the framework's recursion limit is the only guard. It
    exists only for scripts/demo_graph.py and its test. allow_questions needs a checkpointer (a pause has to be saved)."""
    if allow_questions and checkpointer is None:
        raise ValueError("allow_questions needs a checkpointer: a paused run has to be saved somewhere")
    fmt = response_format()

    async def call_model(state: AgentState) -> dict[str, Any]:
        round_no = state["round"] + 1
        if enforce_round_limit and round_no > max_rounds:
            return {"result": _result(Status.MAX_ROUNDS, max_rounds, error=f"no final answer within {max_rounds} model calls")}

        tools.context.load(state["tool_context"])   # the tools' context lives in the state, not in this process
        events: list[dict[str, Any]] = []
        call_started = time.perf_counter()
        try:
            if spend is not None:
                spend.check()
            kwargs: dict[str, Any] = {}
            call_messages = state["messages"]
            forced = _forced_final_reason(tools, round_no, max_rounds)
            if forced is not None:
                kwargs["tool_choice"] = "none"
                call_messages = state["messages"] + [{"role": "user", "content": _FORCED_NOTES[forced]}]
                events.append({"type": "forced_final", "round": round_no, "reason": forced})
            response = await client.chat.completions.create(
                model=model, temperature=temperature, messages=call_messages, tools=definitions,
                response_format=fmt, **kwargs)
        except BudgetExceeded as e:
            return {"events": events, "result": _result(Status.ERROR, round_no - 1, error=str(e))}
        except openai.OpenAIError as e:
            code = getattr(e, "status_code", None)
            error = f"LLM call failed: {type(e).__name__}" + (f" (HTTP {code})" if code else "")
            return {"events": events, "result": _result(Status.ERROR, round_no, error=error)}

        message = response.choices[0].message
        tool_calls = list(message.tool_calls or [])
        update: dict[str, Any] = {"round": round_no, "llm_calls": state["llm_calls"] + 1}
        if getattr(response, "usage", None) is not None:
            prompt, completion = response.usage.prompt_tokens, response.usage.completion_tokens
            update["prompt_tokens"] = state["prompt_tokens"] + prompt
            update["completion_tokens"] = state["completion_tokens"] + completion
            update["cost_usd"] = state["cost_usd"] + (spend.add(model, prompt, completion) if spend is not None
                                                      else cost_usd(model, prompt, completion))
        events.append({"type": "llm_call", "round": round_no, "finish_reason": response.choices[0].finish_reason,
                       "prompt_tokens": getattr(response.usage, "prompt_tokens", None),
                       "completion_tokens": getattr(response.usage, "completion_tokens", None),
                       "duration_ms": int((time.perf_counter() - call_started) * 1000),
                       "tool_calls": [c.function.name for c in tool_calls]})

        if tool_calls:
            update["active_ms"] = state["active_ms"] + int((time.perf_counter() - call_started) * 1000)
            return {**update, "events": events, "pending_calls": [
                {"id": c.id, "name": c.function.name, "arguments": c.function.arguments} for c in tool_calls],
                "messages": [{"role": "assistant", "content": message.content, "tool_calls": [
                    {"id": c.id, "type": "function", "function": {"name": c.function.name, "arguments": c.function.arguments}}
                    for c in tool_calls]}]}

        # A reply without tool requests is the final answer.
        content = message.content or ""
        update["raw"] = content
        try:
            recommendation = Recommendation.model_validate_json(content)
        except ValidationError as e:
            problems = "; ".join(f"{'.'.join(str(p) for p in err['loc']) or 'answer'}: {err['msg']}" for err in e.errors())
            retries = state["format_retries"]
            events.append({"type": "format_retry" if retries < max_format_retries else "format_failed",
                           "round": round_no, "errors": problems})
            update["active_ms"] = state["active_ms"] + int((time.perf_counter() - call_started) * 1000)
            if retries >= max_format_retries or round_no == max_rounds:
                error = f"final answer was not valid (corrections used: {retries}): {problems}"
                return {**update, "events": events, "result": _result(Status.PARSE_FAILED, round_no, error=error)}
            return {**update, "events": events, "format_retries": retries + 1, "messages": [
                {"role": "assistant", "content": content},
                {"role": "user", "content": f"Your answer was not valid: {problems}. "
                                            "Reply again with ONLY the corrected JSON object."}]}

        # Verification is enforced here in code, whether or not the model called the verify tool itself.
        outcome = await tools.execute("verify_recommendation", json.dumps({"recommendation": recommendation.model_dump(mode="json")}))
        report = json.loads(outcome.content)
        update["report"] = report
        update["active_ms"] = state["active_ms"] + int((time.perf_counter() - call_started) * 1000)
        events.append({"type": "verify", "round": round_no, "ok": bool(report.get("ok")),
                       "issues": [i["code"] for i in report.get("issues", [])]})
        if outcome.is_error:
            return {**update, "events": events, "result": _result(
                Status.ERROR, round_no, error=f"verification could not run: {report.get('error')}")}
        if report["ok"]:
            return {**update, "events": events, "result": _result(Status.SUCCESS, round_no, recommendation=recommendation)}
        if state["verify_retries"] >= max_verify_retries or round_no == max_rounds:
            return {**update, "events": events, "result": _result(
                Status.UNVERIFIED, round_no, recommendation=recommendation,
                error=f"answer still failed verification: {_issue_lines(report)}")}
        return {**update, "events": events, "verify_retries": state["verify_retries"] + 1, "messages": [
            {"role": "assistant", "content": content},
            {"role": "user", "content": f"Verification failed: {_issue_lines(report)}. Fix these problems, choose "
                                        "another meal, or answer with meal_id null, and reply with ONLY the JSON object."}]}

    async def run_tools(state: AgentState) -> dict[str, Any]:
        started = time.perf_counter()
        tools.context.load(state["tool_context"])
        seen = dict(state["seen_calls"])
        messages: list[dict[str, Any]] = []
        events: list[dict[str, Any]] = []
        # Every request gets its own role=tool message (matched by id), all in place before the next model call.
        for call in state["pending_calls"]:
            key = call["name"] + "|" + json.dumps(_loads_or_raw(call["arguments"]), sort_keys=True)
            if key in seen:
                outcome = ToolOutcome(call["name"], json.dumps({"error": (
                    f"duplicate call: you already made exactly this call and it returned {seen[key]}. "
                    "Repeating it will not change the result; use what you have or finish.")}),
                    True, 0, "duplicate call skipped")
            else:
                outcome = await tools.execute(call["name"], call["arguments"])
                seen[key] = outcome.summary
            events.append({"type": "tool_call", "round": state["round"], "tool": call["name"],
                           "arguments": _loads_or_raw(call["arguments"]), "is_error": outcome.is_error,
                           "summary": outcome.summary, "duration_ms": outcome.duration_ms})
            messages.append({"role": "tool", "tool_call_id": call["id"], "content": outcome.content})
        return {"messages": messages, "events": events, "pending_calls": [], "seen_calls": seen,
                "tool_context": tools.context.to_dict(),
                "active_ms": state["active_ms"] + int((time.perf_counter() - started) * 1000)}

    def ask_user(state: AgentState) -> dict[str, Any]:
        # Everything above interrupt() runs again when the run resumes (the node restarts from its first line), so it
        # must have no side effects: it only builds the question from the state.
        question = budget_question(state)
        answer = interrupt(question)

        context = dict(state["tool_context"])
        decision = answer.get("decision") if isinstance(answer, dict) else None
        note = str(answer.get("note") or "")[:MAX_NOTE_CHARS] if isinstance(answer, dict) else ""
        new_budget = answer.get("max_price") if isinstance(answer, dict) else None
        current = question["current_max_price"]
        valid_raise = (decision == "raise_budget" and isinstance(new_budget, (int, float)) and not isinstance(new_budget, bool)
                       and current < new_budget <= MAX_BUDGET)
        events = [{"type": "interrupt", "round": state["round"], "question": question},
                  {"type": "resume", "round": state["round"], "decision": decision if valid_raise or decision == "decline" else "invalid",
                   "max_price": new_budget if valid_raise else None, "note": note or None}]
        if valid_raise:
            # The budget the verifier enforces becomes the new one, so the model cannot drift above what the user allowed.
            context["max_price"] = float(new_budget)
            context["empty_hard_searches"] = []
            allergens = ", ".join(question["excluded_allergens"]) or "none"
            message = (f"The user agreed to raise the budget to ${new_budget:g}. Search again with max_price={new_budget:g}. "
                       f"The allergen limits ({allergens}) have NOT changed and must stay."
                       + (f" The user added: {note}" if note else ""))
        else:
            # A decline (or an answer that makes no sense) never relaxes anything: the hard-constraints-unsatisfiable
            # rule stays in force and the next model call has to answer that nothing fits.
            message = ("The user does not want to raise the budget"
                       + (f" and added: {note}." if note else ".")
                       + " Answer now with meal_id null, explain that nothing fits their budget and allergen limits, "
                         "and mention what they said if it is relevant.")
        return {"messages": [{"role": "user", "content": message}], "events": events, "tool_context": context,
                "asks": state["asks"] + 1}

    def route(state: AgentState) -> str:
        if state["result"] is not None:
            return END
        return "run_tools" if state["pending_calls"] else "call_model"

    def route_after_tools(state: AgentState) -> str:
        """Pause only when a search that used the hard constraints alone found nothing AND a budget was part of it:
        the budget is the only thing a person may relax. Empty because of allergens alone: nothing to offer."""
        if not allow_questions or state["asks"] >= max_questions:
            return "call_model"
        empty = state["tool_context"]["empty_hard_searches"]
        return "ask_user" if empty and empty[-1][0] is not None else "call_model"

    graph = StateGraph(AgentState)
    graph.add_node("call_model", call_model)
    graph.add_node("run_tools", run_tools)
    graph.add_node("ask_user", ask_user)
    graph.add_edge(START, "call_model")
    graph.add_conditional_edges("call_model", route, {"run_tools": "run_tools", "call_model": "call_model", END: END})
    graph.add_conditional_edges("run_tools", route_after_tools, {"ask_user": "ask_user", "call_model": "call_model"})
    graph.add_edge("ask_user", "call_model")
    return graph.compile(checkpointer=checkpointer)


def initial_state(user_message: str, system_prompt: str, tools: AgentTools, *, model: str, temperature: float,
                  max_rounds: int, max_format_retries: int, max_verify_retries: int, prompt_version: str,
                  meta: dict[str, Any] | None = None) -> AgentState:
    return {
        "messages": [{"role": "system", "content": system_prompt}, {"role": "user", "content": user_message}],
        "events": [{"type": "run_start", "model": model, "temperature": temperature, "prompt_version": prompt_version,
                    "max_rounds": max_rounds, "max_format_retries": max_format_retries,
                    "max_verify_retries": max_verify_retries, "user_message": user_message}],
        "round": 0, "format_retries": 0, "verify_retries": 0, "asks": 0, "pending_calls": [], "seen_calls": {},
        "tool_context": tools.context.to_dict(), "meta": meta or {},
        "llm_calls": 0, "prompt_tokens": 0, "completion_tokens": 0, "cost_usd": 0.0, "active_ms": 0,
        "raw": None, "report": None, "result": None,
    }


class PausedRunError(Exception):
    """Base for the reasons a resume cannot proceed; `.code` is one of not_found, finished, forbidden."""

    code = "error"


class RunNotFound(PausedRunError):
    code = "not_found"


class RunFinished(PausedRunError):
    code = "finished"


class RunForbidden(PausedRunError):
    code = "forbidden"


def _to_run(final: dict[str, Any], tools: AgentTools, *, trace_id: str, model: str, prompt_version: str,
            question: dict[str, Any] | None = None, result: dict[str, Any] | None = None, wall_ms: int = 0) -> AgentRun:
    usage = Usage(llm_calls=final["llm_calls"], prompt_tokens=final["prompt_tokens"],
                  completion_tokens=final["completion_tokens"], cost_usd=final["cost_usd"])
    duration = max(final["active_ms"], wall_ms) if question is None else final["active_ms"]
    if question is not None:  # paused: no result yet, no closing snapshot
        return AgentRun(trace_id=trace_id, status=Status.NEEDS_INPUT, recommendation=None, raw_final=final["raw"],
                        rounds=final["round"], events=list(final["events"]), usage=usage, model=model,
                        prompt_version=prompt_version, duration_ms=duration, verification=final["report"], error=None,
                        question=question)
    assert result is not None
    status = Status(result["status"])
    recommendation = Recommendation.model_validate(result["recommendation"]) if result["recommendation"] is not None else None
    events = final["events"] + [final_event(status, result["rounds"], usage, tools, recommendation, final["raw"], result["error"])]
    return AgentRun(trace_id=trace_id, status=status, recommendation=recommendation, raw_final=final["raw"],
                    rounds=result["rounds"], events=events, usage=usage, model=model, prompt_version=prompt_version,
                    duration_ms=duration, verification=final["report"], error=result["error"])


async def _drive(graph: Any, graph_input: Any, config: dict[str, Any], tools: AgentTools, *, trace_id: str, model: str,
                 prompt_version: str, fallback_state: dict[str, Any], on_step: Any, started: float,
                 checkpointed: bool) -> AgentRun:
    wall = lambda: int((time.perf_counter() - started) * 1000)  # noqa: E731
    latest = fallback_state
    try:
        # stream_mode="values" yields the full state after every step, so we always hold the latest one.
        async for chunk in graph.astream(graph_input, config=config, stream_mode="values"):
            if "messages" in chunk:        # a pause adds a "__interrupt__" chunk that is not a state
                latest = chunk
                if on_step is not None:
                    on_step(latest)
    except GraphRecursionError:
        result = _result(Status.ERROR, latest["round"], error=f"graph recursion limit ({config['recursion_limit']}) reached")
        return _to_run(latest, tools, trace_id=trace_id, model=model, prompt_version=prompt_version, result=result, wall_ms=wall())
    if checkpointed:
        snapshot = await graph.aget_state(config)
        if snapshot.next:                      # stopped before finishing: it is waiting at an interrupt
            return _to_run(snapshot.values, tools, trace_id=trace_id, model=model, prompt_version=prompt_version,
                           question=snapshot.interrupts[0].value, wall_ms=wall())
    result = latest["result"] or _result(Status.ERROR, latest["round"], error="graph ended without a result")
    return _to_run(latest, tools, trace_id=trace_id, model=model, prompt_version=prompt_version, result=result, wall_ms=wall())


def _config(thread_id: str, max_rounds: int) -> dict[str, Any]:
    # Each round is at most two steps (model, tools) and a question adds one; the recursion limit is the framework's own
    # backstop on top of ours.
    return {"recursion_limit": 2 * max_rounds + 6, "configurable": {"thread_id": thread_id}}


async def run_agent_graph(
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
    enforce_round_limit: bool = True,
    on_step: Any = None,
    checkpointer: Any = None,
    allow_questions: bool = False,
    meta: dict[str, Any] | None = None,
) -> AgentRun:
    """Drop-in for app.agent.run_agent. on_step(state) is called after every step (for demos).

    With allow_questions (and a checkpointer) the run may end with status NEEDS_INPUT instead of an answer; the saved run
    is continued with resume_agent_graph(thread_id=run.trace_id)."""
    started = time.perf_counter()
    trace_id = trace_id or f"run_{uuid.uuid4().hex}"
    state = initial_state(user_message, system_prompt, tools, model=model, temperature=temperature, max_rounds=max_rounds,
                          max_format_retries=max_format_retries, max_verify_retries=max_verify_retries,
                          prompt_version=prompt_version, meta=meta)
    try:
        definitions = await tools.definitions()
    except ToolError as e:
        return _to_run(state, tools, trace_id=trace_id, model=model, prompt_version=prompt_version,
                       result=_result(Status.ERROR, 0, error=f"could not load tool definitions: {e}"))

    graph = build_agent_graph(client, tools, definitions, model=model, temperature=temperature, max_rounds=max_rounds,
                              max_format_retries=max_format_retries, max_verify_retries=max_verify_retries, spend=spend,
                              enforce_round_limit=enforce_round_limit, checkpointer=checkpointer,
                              allow_questions=allow_questions)
    return await _drive(graph, state, _config(trace_id, max_rounds), tools, trace_id=trace_id, model=model,
                        prompt_version=prompt_version, fallback_state=state, on_step=on_step, started=started,
                        checkpointed=checkpointer is not None)


async def read_paused(checkpointer: Any, thread_id: str, *, user_id: int | None = None) -> dict[str, Any]:
    """The saved state of a paused run, or the reason it cannot be resumed (RunNotFound / RunFinished / RunForbidden)."""
    saved = await checkpointer.aget_tuple({"configurable": {"thread_id": thread_id}})
    if saved is None:
        raise RunNotFound(thread_id)
    values = saved.checkpoint["channel_values"]
    if values.get("result") is not None:
        raise RunFinished(thread_id)
    if user_id is not None and values["meta"].get("user_id") != user_id:
        raise RunNotFound(thread_id)   # same answer as "unknown": do not reveal that someone else's run exists
    return values


async def resume_agent_graph(
    client: Any,
    tools: AgentTools,
    *,
    thread_id: str,
    decision: dict[str, Any],
    checkpointer: Any,
    model: str,
    prompt_version: str = "v1",
    temperature: float = 0.0,
    max_rounds: int = 8,
    max_format_retries: int = 2,
    max_verify_retries: int = 2,
    spend: SpendTracker | None = None,
    user_id: int | None = None,
    on_step: Any = None,
) -> AgentRun:
    """Continue a run that paused for a question. `tools` may be brand new (a new process): its context is loaded from the
    saved state. Raises RunNotFound / RunFinished for a thread that cannot be resumed."""
    started = time.perf_counter()
    saved = await read_paused(checkpointer, thread_id, user_id=user_id)
    tools.context.load(saved["tool_context"])
    definitions = await tools.definitions()
    graph = build_agent_graph(client, tools, definitions, model=model, temperature=temperature, max_rounds=max_rounds,
                              max_format_retries=max_format_retries, max_verify_retries=max_verify_retries, spend=spend,
                              checkpointer=checkpointer, allow_questions=True)
    snapshot = await graph.aget_state(_config(thread_id, max_rounds))
    if not snapshot.next:
        raise RunFinished(thread_id)
    return await _drive(graph, Command(resume=decision), _config(thread_id, max_rounds), tools, trace_id=thread_id,
                        model=model, prompt_version=prompt_version, fallback_state=dict(snapshot.values), on_step=on_step,
                        started=started, checkpointed=True)
