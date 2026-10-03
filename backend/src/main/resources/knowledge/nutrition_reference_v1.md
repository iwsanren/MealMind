# Nutrition Reference v1

Curated, non-clinical nutrition reference for grounding meal-recommendation
explanations. Scope is intentionally limited to general-adult, everyday
guidance — content aimed at special populations (pregnancy, infancy/childhood,
chronic disease) is excluded on purpose, because RiskGuardService already
routes those topics to a conservative fixed message instead of a generated
answer. Citing guideline text about pregnancy nutrition would be pointless:
the system is designed to never generate that kind of advice in the first
place.

Each block below is tagged with its source so the LLM (and a human reviewer)
can trace a claim back to where it came from. See
`docs/rag-knowledge-base-decision.md` for why this is a flat static file
instead of a chunked + embedded vector store.

---

## Source 1: Dietary Guidelines for Americans, 2025-2030
Official U.S. Department of Health and Human Services / U.S. Department of
Agriculture publication, released January 2026. Retrieved from
https://cdn.realfood.gov/DGA_508.pdf. General-adult sections only (pages 1-5
of the 10-page PDF); pages 6-9 ("Special Populations & Considerations" —
infancy, childhood, adolescence, pregnancy, lactation, older adults, chronic
disease, vegetarian/vegan) are deliberately omitted for the reason above.
Lines specific to pregnant women or children within the pages-1-5 sections
(e.g. alcohol, sodium) are also trimmed for the same reason.

[Source: DGA 2025-2030, "Eat the Right Amount for You"]
Calorie needs depend on age, sex, height, weight, and physical activity
level. Pay attention to portion sizes, particularly for foods and beverages
higher in calories. Choose water (still or sparkling) and unsweetened
beverages for hydration.

[Source: DGA 2025-2030, "Prioritize Protein Foods at Every Meal"]
Prioritize high-quality, nutrient-dense protein foods as part of a healthy
dietary pattern. Consume a variety of protein foods from animal sources
(eggs, poultry, seafood, red meat) and plant sources (beans, peas, lentils,
legumes, nuts, seeds, soy). Prefer meat with no or limited added sugars,
refined carbohydrates/starches, or chemical additives. Prefer baked, broiled,
roasted, stir-fried, or grilled cooking methods over deep-frying.
Protein serving goal: 1.2-1.6 grams of protein per kilogram of body weight
per day, adjusted for individual caloric requirements.

[Source: DGA 2025-2030, "Consume Dairy"]
Full-fat dairy with no added sugars is an excellent source of protein,
healthy fats, vitamins, and minerals. Dairy serving goal: 3 servings per day
as part of a 2,000-calorie dietary pattern, adjusted for individual caloric
requirements.

[Source: DGA 2025-2030, "Eat Vegetables & Fruits Throughout the Day"]
Eat a variety of colorful, nutrient-dense vegetables and fruits, ideally
whole and in their original form. Frozen, dried, or canned vegetables/fruits
with no or very limited added sugars are also good options. Serving goals
for a 2,000-calorie dietary pattern: vegetables 3 servings/day, fruits 2
servings/day, adjusted for individual caloric requirements.

[Source: DGA 2025-2030, "Incorporate Healthy Fats"]
Healthy fats are plentiful in whole foods such as meats, poultry, eggs,
omega-3-rich seafood, nuts, seeds, full-fat dairy, olives, and avocados.
When cooking, prioritize oils with essential fatty acids, such as olive oil.
Saturated fat consumption should generally not exceed 10% of total daily
calories.

[Source: DGA 2025-2030, "Focus on Whole Grains"]
Prioritize fiber-rich whole grains and significantly reduce consumption of
highly processed, refined carbohydrates (e.g. white bread, packaged breakfast
items, flour tortillas, crackers). Serving goal: 2-4 servings per day,
adjusted for individual caloric requirements.

[Source: DGA 2025-2030, "Limit Highly Processed Foods, Added Sugars, & Refined Carbohydrates"]
Limit highly processed, packaged, or ready-to-eat foods that are salty or
sweet and high in added sugars/sodium; prefer nutrient-dense, home-prepared
options. No amount of added sugars is recommended as part of a nutritious
diet; as a rough guide, one meal should contain no more than 10 grams of
added sugars. Naturally occurring sugars in foods such as fruit and plain
milk are not considered added sugars.

[Source: DGA 2025-2030, "Sodium" (general-population line only)]
The general population, ages 14 and above, should consume less than 2,300 mg
of sodium per day. Highly processed foods that are high in sodium should be
avoided.

---

## Source 2: FDA nutrient content claim definitions (21 CFR 101.54)
**Paraphrased summary**, not verbatim regulatory text — the fetch tool used
to retrieve this returned a summary rather than the full section text.
Before this is used to justify a specific "high protein" / "good source of
X" claim in a shipped feature, verify the exact wording and current
reference amounts against the official text at
https://www.ecfr.gov/current/title-21/chapter-I/subchapter-B/part-101/subpart-D/section-101.54
or https://www.law.cornell.edu/cfr/text/21/101.54 — reference amounts
("RACC") are defined per food category elsewhere in 21 CFR 101 and are not
reproduced here.

[Source: FDA 21 CFR 101.54(b) — "High" / "Rich in" / "Excellent source of"]
A food may bear this claim for a nutrient if it contains 20% or more of the
Reference Daily Intake (RDI) or Daily Reference Value (DRV) for that
nutrient per reference amount customarily consumed.

[Source: FDA 21 CFR 101.54(c) — "Good source" / "Contains" / "Provides"]
A food may bear this claim for a nutrient if it contains 10-19% of the RDI
or DRV for that nutrient per reference amount customarily consumed.

[Source: FDA 21 CFR 101.54(e) — "More" / "Fortified" / "Enriched" / "Added" / "Extra" / "Plus"]
A food may bear this claim if it contains at least 10% more of the RDI/DRV
for a nutrient than a stated reference food, with the reference food's
identity and the percentage difference disclosed.

---

## Source 3: USDA MyPlate — general framework
**Not directly verified** — myplate.gov blocked automated fetching (HTTP
403) when this file was prepared, so this section is a paraphrase from
well-established public knowledge of the MyPlate framework, not a captured
quote. Verify against https://www.myplate.gov/eat-healthy/what-is-myplate
before treating any specific wording here as a citable source.

[Source: USDA MyPlate framework — general description, unverified against live page]
MyPlate organizes food into five groups — fruits, vegetables, grains,
protein foods, and dairy — and illustrates a balanced plate as roughly half
fruits and vegetables, with the remainder split between grains and protein
foods, plus a side of dairy. It is a simplified, visual companion to the
Dietary Guidelines for Americans rather than a separate source of numeric
targets.
