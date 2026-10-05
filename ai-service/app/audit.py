"""Persist an agent run to the backend's trace table (the "dashcam").

Policy: recording is best effort. If the backend cannot take the trace, the user's answer is still returned and the
failure is logged (class name only) and reported to the caller. That fits a low-stakes meal recommender; a system
under audit-trail legal requirements would do the opposite and refuse to answer without a stored record.
"""

import logging
from dataclasses import dataclass

from app.agent import AgentRun
from app.backend import ToolError

logger = logging.getLogger("ai-service.audit")


@dataclass(frozen=True)
class TraceResult:
    written: bool
    error: str | None = None


async def record_run(backend, run: AgentRun, *, session_id: str, user_id: int) -> TraceResult:
    try:
        await backend.write_trace(run.trace_payload(session_id, user_id))
    except ToolError as e:
        logger.warning("trace %s not stored: %s", run.trace_id, e)
        return TraceResult(written=False, error=str(e))
    return TraceResult(written=True)
