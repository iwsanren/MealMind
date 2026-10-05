"""Step-3 experiment: does the wording of a tool description change what the model does? (REAL API, 6 calls total.)

    python -m scripts.experiment_vague_description

Same user message and parameter schemas. Variants: GOOD (full descriptions); VAGUE-TOP (only search_meals' top-level
description is replaced by "Search for meals."); VAGUE-ALL (also every parameter description is removed).
Results are anecdotal (2 samples per variant at temperature 0), a demonstration of the mechanism, not a measurement.
"""

import asyncio
import copy
import json

from app.backend import BackendClient
from app.config import get_settings
from app.llm import make_client
from app.nutrition import NutritionReference
from app.tools import AgentTools, RunContext

USER_MESSAGE = "I want a high-protein dinner tonight, under $15, and I'm allergic to shellfish. What should I eat?"
VAGUE = "Search for meals."
SAMPLES_PER_VARIANT = 2


async def main() -> None:
    settings = get_settings()
    backend = BackendClient(settings.internal_api_base_url, settings.backend_timeout_s)
    tools = AgentTools(backend, NutritionReference.from_file(settings.nutrition_reference_file), RunContext(user_id=1))
    client = make_client(settings)
    good = await tools.definitions()
    vague_top = copy.deepcopy(good)
    vague_top[0]["function"]["description"] = VAGUE
    vague_all = copy.deepcopy(vague_top)
    for prop in vague_all[0]["function"]["parameters"]["properties"].values():
        prop.pop("description", None)
    try:
        for label, definitions in (("GOOD", good), ("VAGUE-TOP", vague_top), ("VAGUE-ALL", vague_all)):
            print(f"\n##### {label} -> {definitions[0]['function']['description'][:60]!r}...")
            for i in range(SAMPLES_PER_VARIANT):
                response = await client.chat.completions.create(
                    model=settings.agent_model, temperature=settings.agent_temperature,
                    messages=[{"role": "system", "content": "You recommend meals. Use the tools."},
                              {"role": "user", "content": USER_MESSAGE}],
                    tools=definitions)
                message = response.choices[0].message
                calls = [(c.function.name, json.loads(c.function.arguments)) for c in message.tool_calls or []]
                print(f"sample {i + 1}: {json.dumps(calls)}" if calls else f"sample {i + 1}: NO TOOL CALL, said: {message.content!r}")
    finally:
        await backend.aclose()
        await client.close()


if __name__ == "__main__":
    asyncio.run(main())
