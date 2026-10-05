"""Pause, kill the process, restart, resume: against the REAL backend and the REAL model (a few cents).

    python -m scripts.demo_pause_resume                                  # answer: raise the budget to $20
    python -m scripts.demo_pause_resume --decision decline --note "never mind, I will cook"

Needs the Java backend running (tools search its meal library). The script starts ai-service itself, on port 8011 with
AGENT_ENGINE=langgraph and its own checkpoint file, and does this:

  1. POST /v1/recommend with allow_questions  -> the agent searches, finds nothing under the budget and PAUSES with a question
  2. prints what was saved at the pause (read straight from the SQLite file)
  3. KILLS the ai-service process
  4. starts a NEW ai-service process
  5. POST /v1/recommend/{thread_id}/resume    -> continues from the pause and finishes (equivalent curl commands are printed)
"""

import argparse
import asyncio
import json
import os
import subprocess
import sys
import time
from pathlib import Path

import httpx
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver

from app.agent_graph import read_paused

PORT = 8011
BASE = f"http://127.0.0.1:{PORT}"
DB = Path(__file__).resolve().parents[1] / "data" / "demo_checkpoints.sqlite"


def start_server() -> subprocess.Popen:
    env = {**os.environ, "AGENT_ENGINE": "langgraph", "AGENT_CHECKPOINT_DB": str(DB)}
    process = subprocess.Popen([sys.executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", str(PORT)],
                               env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(60):
        try:
            if httpx.get(f"{BASE}/health", timeout=1).status_code == 200:
                return process
        except httpx.HTTPError:
            time.sleep(0.5)
    process.kill()
    raise SystemExit("ai-service did not start")


async def saved_state(thread_id: str, user_id: int) -> dict:
    async with AsyncSqliteSaver.from_conn_string(str(DB)) as saver:
        return await read_paused(saver, thread_id, user_id=user_id)


def show(title: str, payload) -> None:
    print(f"\n=== {title} ===")
    print(json.dumps(payload, indent=2, ensure_ascii=False) if not isinstance(payload, str) else payload)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--decision", choices=["raise_budget", "decline"], default="raise_budget")
    parser.add_argument("--max-price", type=float, default=20)
    parser.add_argument("--note", default=None)
    parser.add_argument("--message", default="I want a dinner under $2, I am allergic to shellfish")
    args = parser.parse_args()

    DB.parent.mkdir(parents=True, exist_ok=True)
    DB.unlink(missing_ok=True)
    request = {"user_message": args.message, "session_id": "sess_demo_pause", "user_id": 1, "source_mode": "PUBLIC",
               "allow_questions": True}

    server = start_server()
    try:
        first = httpx.post(f"{BASE}/v1/recommend", json=request, timeout=60).json()
        show("1. first call (process A)", {k: first[k] for k in ("status", "thread_id", "question", "trace_written", "llm_calls", "cost_usd")})
        if first["status"] != "NEEDS_INPUT":
            raise SystemExit("the agent did not pause; nothing to resume (try a lower budget)")
        thread_id = first["thread_id"]

        saved = asyncio.run(saved_state(thread_id, 1))
        show("2. what was saved at the pause (state in the SQLite file)", {
            "messages": [m["role"] + (" [tool request]" if m.get("tool_calls") else "") for m in saved["messages"]],
            "round": saved["round"], "asks": saved["asks"], "meta": saved["meta"],
            "tool_context.max_price": saved["tool_context"]["max_price"],
            "tool_context.exclude_allergens": saved["tool_context"]["exclude_allergens"],
            "tool_context.empty_hard_searches": saved["tool_context"]["empty_hard_searches"],
            "events so far": [e["type"] for e in saved["events"]],
        })
    finally:
        server.kill()
        server.wait()
    print(f"\n=== 3. process A killed (pid {server.pid}); port {PORT} answers: ", end="")
    try:
        httpx.get(f"{BASE}/health", timeout=1)
        print("STILL UP?!")
    except httpx.HTTPError:
        print("nothing (connection refused)")

    server = start_server()
    try:
        answer = {"user_id": 1, "decision": args.decision}
        if args.decision == "raise_budget":
            answer["max_price"] = args.max_price
        if args.note:
            answer["note"] = args.note
        print(f"\n=== 4. new process B (pid {server.pid}); the equivalent curl command:")
        print(f"curl -s -X POST {BASE}/v1/recommend/{thread_id}/resume -H 'Content-Type: application/json' -d '{json.dumps(answer)}'")
        resumed = httpx.post(f"{BASE}/v1/recommend/{thread_id}/resume", json=answer, timeout=60).json()
        show("5. resumed in process B", {k: resumed.get(k) for k in ("status", "trace_id", "trace_written", "rounds", "llm_calls", "cost_usd", "error")})
        recommendation = resumed.get("recommendation")
        if recommendation:
            print(f"\nanswer: meal_id={recommendation['meal_id']} {recommendation['meal_name']!r}")
            print("reason:", recommendation["reason"] or recommendation.get("no_match_reason"))
            if recommendation["meal_id"] is None:
                print("no_match_reason:", recommendation.get("no_match_reason"))
        again = httpx.post(f"{BASE}/v1/recommend/{thread_id}/resume", json=answer, timeout=60)
        print(f"\n=== 6. the same resume again: HTTP {again.status_code} {again.json()}")
        bad = httpx.post(f"{BASE}/v1/recommend/run_does_not_exist/resume", json=answer, timeout=60)
        print(f"=== 7. a thread id that does not exist: HTTP {bad.status_code} {bad.json()}")
    finally:
        server.kill()
        server.wait()


if __name__ == "__main__":
    main()
