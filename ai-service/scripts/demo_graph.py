"""Watch the LangGraph agent run, with a scripted fake model and a fake backend: no network, no cost.

    python -m scripts.demo_graph                       # prints the state after every step
    python -m scripts.demo_graph --png ../notes/agent/graph.png    # also draws the graph (needs internet: mermaid.ink)
    python -m scripts.demo_graph --no-round-limit      # break it: remove OUR round limit, see the framework's recursion limit

--png sends only the graph's structure (node names) to https://mermaid.ink to render the picture; the Mermaid text is
always written next to it as .mmd so the picture can be rebuilt offline.
"""

import argparse
import asyncio
import copy
import json
from pathlib import Path
from types import SimpleNamespace

from app.agent import format_timeline
from app.agent_graph import build_agent_graph, run_agent_graph
from app.config import DEFAULT_NUTRITION_FILE
from app.fake_backend import FakeBackend
from app.nutrition import NutritionReference
from app.tools import AgentTools, RunContext

SLOT_OPTIONS = {"mealTime": ["Breakfast", "Lunch", "Dinner"], "mood": [], "scene": [], "healthGoal": ["High Protein"],
                "cuisine": [], "taste": [], "convenience": []}
MEALS = [
    {"id": 1, "sourceType": "PUBLIC", "ownerUserId": None, "name": "Chicken Bowl", "mealTime": ["Dinner"], "mood": [],
     "scene": [], "healthGoal": ["High Protein"], "cuisine": [], "taste": [], "convenience": [], "price": 12.5,
     "proteinG": 38.0, "calories": 520, "allergens": ["milk"]},
    {"id": 2, "sourceType": "PUBLIC", "ownerUserId": None, "name": "Shrimp Pasta", "mealTime": ["Dinner"], "mood": [],
     "scene": [], "healthGoal": ["High Protein"], "cuisine": [], "taste": [], "convenience": [], "price": 14.0,
     "proteinG": 24.0, "calories": 640, "allergens": ["shellfish", "wheat"]},
]
FINAL = {"meal_id": 1, "meal_name": "Chicken Bowl", "reason": "High protein and under budget.", "no_match_reason": None,
         "claims": [{"text": "$12.50", "source": "FROM_CANDIDATE", "field": "PRICE", "number": 12.5, "allergen": None,
                     "tag": None, "guideline_ref": None}]}


class ScriptedModel:
    """Replies from a script; when `endless`, keeps asking for a different search forever."""

    def __init__(self, endless: bool):
        self.calls = 0
        self.endless = endless
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self._create))

    async def _create(self, **kwargs):
        self.calls += 1
        if self.endless or self.calls == 1:
            args = {"max_price": 100 - self.calls, "exclude_allergens": ["shellfish"]}
            call = SimpleNamespace(id=f"call_{self.calls}", function=SimpleNamespace(name="search_meals", arguments=json.dumps(args)))
            message, finish = SimpleNamespace(content=None, tool_calls=[call]), "tool_calls"
        else:
            message, finish = SimpleNamespace(content=json.dumps(FINAL), tool_calls=None), "stop"
        return SimpleNamespace(choices=[SimpleNamespace(message=message, finish_reason=finish)],
                               usage=SimpleNamespace(prompt_tokens=100, completion_tokens=20))


def describe(state: dict, previous: dict | None) -> str:
    """One line: what changed in the state since the previous step."""
    if previous is None:
        return f"input: {len(state['messages'])} messages ({', '.join(m['role'] for m in state['messages'])})"
    added = [m["role"] + (f"[{len(m['tool_calls'])} tool request]" if m.get("tool_calls") else "")
             for m in state["messages"][len(previous["messages"]):]]
    new_events = [e["type"] + (f":{e['tool']}" if "tool" in e else "") for e in state["events"][len(previous["events"]):]]
    pending = [c["name"] for c in state["pending_calls"]]
    done = f", RESULT={state['result']['status']}" if state["result"] else ""
    return (f"round={state['round']} | +messages {added or '-'} | +events {new_events or '-'} | "
            f"pending tools {pending or '-'}{done}")


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--png", default=None, help="write the graph drawing here (needs internet)")
    parser.add_argument("--no-round-limit", action="store_true", help="remove our own round limit (experiment)")
    args = parser.parse_args()

    backend = FakeBackend(MEALS, slot_options=SLOT_OPTIONS)
    tools = AgentTools(backend, NutritionReference.from_file(DEFAULT_NUTRITION_FILE), RunContext(user_id=1))
    model = ScriptedModel(endless=args.no_round_limit)

    if args.png:
        drawing = build_agent_graph(model, tools, await tools.definitions(), model="gpt-4o-mini", temperature=0.0,
                                    max_rounds=8, max_format_retries=2, max_verify_retries=2, spend=None).get_graph()
        target = Path(args.png)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.with_suffix(".mmd").write_text(drawing.draw_mermaid(), encoding="utf-8")
        try:
            target.write_bytes(drawing.draw_mermaid_png())
            print(f"graph drawing: {target}  (Mermaid text: {target.with_suffix('.mmd')})")
        except Exception as e:  # network or renderer failure: the .mmd text is still there
            print(f"could not draw the PNG ({type(e).__name__}); Mermaid text written to {target.with_suffix('.mmd')}")

    steps: list[dict] = []

    def on_step(state: dict) -> None:
        print(f"step {len(steps)}: {describe(state, steps[-1] if steps else None)}")
        steps.append(copy.deepcopy(state))

    run = await run_agent_graph(model, tools, "High protein dinner under $15, allergic to shellfish",
                                model="gpt-4o-mini", system_prompt="SYSTEM", max_rounds=8,
                                enforce_round_limit=not args.no_round_limit, on_step=on_step)
    print()
    print(format_timeline(run))
    print(f"\nstatus={run.status.value} error={run.error!r} model_calls={model.calls}")


if __name__ == "__main__":
    asyncio.run(main())
