"""Run the agent once against the REAL backend and the REAL model (costs a fraction of a cent).

    python -m scripts.demo_agent "High protein dinner under $15, I'm allergic to shellfish"
    python -m scripts.demo_agent --record --session sess_demo "..."     # also store the run in the backend trace table

Needs the Java backend running. Prints the per-round timeline and the final answer. With --record the run can be read
back with GET /api/v1/debug/traces/<traceId> (header X-User-Id) and in the Trace page of the web UI.
"""

import argparse
import asyncio
import json

from app.agent import format_timeline, run_agent
from app.audit import record_run
from app.backend import BackendClient
from app.config import get_settings
from app.llm import make_client
from app.nutrition import NutritionReference
from app.prompts import load_agent_prompt
from app.spend import SpendTracker
from app.tools import AgentTools, RunContext

DEFAULT_MESSAGE = "I want a high-protein dinner tonight, under $15, and I'm allergic to shellfish."


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("message", nargs="*")
    parser.add_argument("--record", action="store_true", help="store the run via POST /internal/v1/traces")
    parser.add_argument("--session", default="sess_agent_demo")
    parser.add_argument("--user", type=int, default=1)
    args = parser.parse_args()

    settings = get_settings()
    message = " ".join(args.message) or DEFAULT_MESSAGE
    backend = BackendClient(settings.internal_api_base_url, settings.backend_timeout_s)
    client = make_client(settings)
    try:
        tools = AgentTools(backend, NutritionReference.from_file(settings.nutrition_reference_file),
                           RunContext(user_id=args.user))
        run = await run_agent(
            client, tools, message, model=settings.agent_model, temperature=settings.agent_temperature,
            system_prompt=load_agent_prompt(settings.agent_prompt_version), prompt_version=settings.agent_prompt_version,
            max_rounds=settings.agent_max_rounds, max_format_retries=settings.agent_max_format_retries,
            max_verify_retries=settings.agent_max_verify_retries, spend=SpendTracker(cap_usd=0.25))
        print(f"user: {message}\n")
        print(format_timeline(run))
        if run.recommendation is not None:
            print("\nfinal answer:\n" + json.dumps(run.recommendation.model_dump(mode="json"), indent=2))
        if run.error:
            print("\nnote:", run.error)
        if args.record:
            outcome = await record_run(backend, run, session_id=args.session, user_id=args.user)
            print(f"\ntrace {run.trace_id}: " + ("stored" if outcome.written else f"NOT stored ({outcome.error})"))
    finally:
        await backend.aclose()
        await client.close()


if __name__ == "__main__":
    asyncio.run(main())
