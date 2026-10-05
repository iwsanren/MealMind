# agent_system prompt versions

| version | status | note |
|---|---|---|
| v1 | history only | First draft. Written for the old claim schema (a single free-text `value` field); not usable with the current schema. |
| v2 | history only | Adds "one fact per claim" and the reason-number rule. Still the old `value` field. A real run with it produced corrupted `value` strings (`"12.5},{"`), which led to the typed claim fields. |
| v3 | superseded | Typed claim fields (`number` / `allergen` / `tag`). A real run for "allergic to shellfish, only $3" looped through 8 searches, alternating between two queries and twice dropping `exclude_allergens`, and never reached "nothing fits". |
| v4 | superseded | Same as v3, but step 4 spells out when to stop searching and answer `meal_id: null`. In the step-6 smoke run (with `verify_recommendation` offered as a tool) the model spent 5 of 8 rounds calling the verify tool on one case. |
| v5 | current | Same as v4 without the `verify_recommendation` step: verification is no longer a model tool, the loop enforces it and reports failures back. |

A prompt that has been used in a real run is never edited in place; a change becomes a new version, and the version is recorded in every trace.
