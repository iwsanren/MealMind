"""Run the four agent tools against the REAL backend, without any LLM call (costs nothing).

    python -m scripts.demo_tools              # scripted walkthrough: search -> verify a good and a bad draft
    python -m scripts.demo_tools --schemas    # print the tool definitions exactly as they are sent to OpenAI

Needs the Java backend running (mvn spring-boot:run in backend/).
"""

import asyncio
import json
import sys

from app.backend import BackendClient
from app.config import get_settings
from app.nutrition import NutritionReference
from app.tools import AgentTools, RunContext


def show(title: str, outcome) -> None:
    print(f"\n=== {title}\n[{outcome.name}] {'ERROR' if outcome.is_error else 'ok'} in {outcome.duration_ms} ms - {outcome.summary}")
    print(json.dumps(json.loads(outcome.content), indent=2)[:1500])


async def main() -> None:
    settings = get_settings()
    backend = BackendClient(settings.internal_api_base_url, settings.backend_timeout_s)
    nutrition = NutritionReference.from_file(settings.nutrition_reference_file)
    tools = AgentTools(backend, nutrition, RunContext(user_id=1))
    try:
        if "--schemas" in sys.argv:
            print(json.dumps(await tools.definitions(), indent=2))
            return

        # User: "high protein dinner, under $15, I'm allergic to shellfish"
        show("1. search_meals with the user's hard constraints",
             await tools.execute("search_meals", json.dumps(
                 {"meal_time": ["Dinner"], "health_goal": ["High Protein"], "max_price": 15, "exclude_allergens": ["shellfish"]})))
        show("2. get_recent_feedback", await tools.execute("get_recent_feedback", json.dumps({"limit": 5})))
        show("3. lookup_nutrition", await tools.execute("lookup_nutrition", json.dumps({"topic": "protein"})))

        good = {"recommendation": {"meal_id": 1, "meal_name": "Grilled Chicken Caesar Salad", "reason": "High in protein and within budget.",
                                   "claims": [{"text": "$12.50", "source": "FROM_CANDIDATE", "field": "PRICE", "value": "12.5"},
                                              {"text": "within your $15 budget", "source": "FROM_CANDIDATE", "field": "WITHIN_BUDGET", "value": "15"},
                                              {"text": "no shellfish", "source": "FROM_CANDIDATE", "field": "ALLERGEN_FREE", "value": "shellfish"},
                                              {"text": "high in protein", "source": "FROM_CANDIDATE", "field": "HIGH_PROTEIN"}]}}
        show("4. verify_recommendation: a correct draft", await tools.execute("verify_recommendation", json.dumps(good)))

        bad = json.loads(json.dumps(good))
        bad["recommendation"]["claims"] = [
            {"text": "only $9", "source": "FROM_CANDIDATE", "field": "PRICE", "value": "9"},
            {"text": "recommended for muscle gain", "source": "FROM_GUIDELINE", "field": "OTHER", "guideline_ref": "Wikipedia, Protein"}]
        show("5. verify_recommendation: a draft with a wrong price and a made-up citation",
             await tools.execute("verify_recommendation", json.dumps(bad)))
        print("\ncandidates seen in this run:", sorted(tools.context.candidates), "| constraints:", tools.context.constraints().model_dump())
    finally:
        await backend.aclose()


if __name__ == "__main__":
    asyncio.run(main())
