"""In-memory stand-in for the Java backend's internal API, used by unit tests and by the offline evaluation.

It mirrors the real semantics that matter to the agent: tag overlap per dimension, hard budget and allergen filters
that fail closed on unknown (null) facts, exclusion of already recommended meals, ranking by mean tag-overlap
ratio (Top 10), and the RiskGuard keyword rules. tests/test_backend_parity.py compares it with the real backend
whenever one is running, so drift is caught instead of assumed away.
"""

from typing import Any

from app.backend import ToolError

_DIMENSIONS = ["mealTime", "mood", "scene", "healthGoal", "cuisine", "taste", "convenience"]
_SEARCH_LIMIT = 50
_TOP_N = 10

# Ported from backend RiskGuardService; keep in sync (substring matching, so "treat" matches "treat myself").
_MEDICAL = ["cure", "cures", "treat", "treats", "treatment", "diagnose", "diagnosis", "medication", "prescription", "prescribe"]
_EXTREME_DIET = ["starve", "starving", "skip all meals", "skip every meal", "only drink water", "water fast",
                 "extreme diet", "zero calorie"]
_ABSOLUTE = ["guaranteed", "guarantee", "healthiest ever", "100% effective", "no side effects"]
_SPECIAL_POPULATION = ["pregnant", "pregnancy", "diabetes", "diabetic", "hypertension", "high blood pressure",
                       "minor", "child", "children", "infant"]
CONSERVATIVE_MESSAGE = (
    "This touches on a health or medical risk, and I can't stand in for a doctor's diagnosis or treatment advice. "
    "Day to day, aim for meals that are light, balanced, and not oversized; if symptoms are significant, or a chronic "
    "condition or pregnancy is involved, please consult a doctor or a registered dietitian."
)


def risk_result(text: str | None) -> dict[str, Any]:
    """The backend's RiskGuard rules (substring matching) applied to one text; blank text is a 400 like the real endpoint."""
    if text is None or not text.strip():
        raise ToolError("backend returned HTTP 400: text is required and must not be blank")
    lowered = text.lower()
    reasons = []
    if any(k in lowered for k in _MEDICAL):
        reasons.append("Mentions medical diagnosis, treatment, or prescription claims")
    if any(k in lowered for k in _EXTREME_DIET):
        reasons.append("Mentions extreme dieting or fasting behavior")
    if any(k in lowered for k in _ABSOLUTE):
        reasons.append("Mentions an absolute health or weight-loss guarantee")
    if any(k in lowered for k in _SPECIAL_POPULATION):
        reasons.append("Mentions a special population or chronic condition")
    if reasons:
        return {"blocked": True, "reasons": reasons, "conservativeMessage": CONSERVATIVE_MESSAGE}
    return {"blocked": False, "reasons": [], "conservativeMessage": None}


class FakeBackend:
    def __init__(self, meals: list[dict[str, Any]], slot_options: dict[str, list[str]] | None = None,
                 feedback: list[dict[str, Any]] | None = None):
        """meals use the backend's JSON shape (id, sourceType, name, <7 tag lists>, price, proteinG, calories, allergens)."""
        self.meals = meals
        self._slot_options = slot_options or {}
        self.feedback = feedback or []
        self.calls: list[tuple[str, Any]] = []
        self.traces: list[dict[str, Any]] = []
        self.fail_with: ToolError | None = None  # set to simulate an outage for every call

    def _enter(self, name: str, payload: Any) -> None:
        self.calls.append((name, payload))
        if self.fail_with is not None:
            raise self.fail_with

    async def slot_options(self) -> dict[str, list[str]]:
        self._enter("slot_options", None)
        return self._slot_options

    async def search_meals(self, body: dict[str, Any]) -> dict[str, Any]:
        self._enter("search_meals", body)
        mode = (body.get("sourceMode") or "").upper()
        if mode not in {"PUBLIC", "PERSONAL"}:
            raise ToolError("backend returned HTTP 400: sourceMode must be PERSONAL or PUBLIC")
        if mode == "PERSONAL" and body.get("userId") is None:
            raise ToolError("backend returned HTTP 400: userId is required for PERSONAL search")

        max_price = body.get("maxPrice")
        exclude_allergens = {a.lower() for a in body.get("excludeAllergens") or []}
        candidates = []
        for meal in self.meals:
            if mode == "PUBLIC" and meal.get("sourceType", "PUBLIC") != "PUBLIC":
                continue
            if mode == "PERSONAL" and (meal.get("sourceType") != "PERSONAL" or meal.get("ownerUserId") != body["userId"]):
                continue
            if any(body.get(d) and not set(body[d]) & set(meal.get(d, [])) for d in _DIMENSIONS):
                continue
            if max_price is not None and (meal.get("price") is None or meal["price"] > max_price):
                continue
            if exclude_allergens:
                allergens = meal.get("allergens")
                if allergens is None or exclude_allergens & {a.lower() for a in allergens}:
                    continue
            candidates.append(meal)
        candidates = candidates[:_SEARCH_LIMIT]

        excluded = set(body.get("excludeMealIds") or [])
        ranked = []
        for meal in candidates:
            if meal["id"] in excluded:
                continue
            total = 0.0
            for d in _DIMENSIONS:
                wanted = body.get(d) or []
                if wanted:
                    total += sum(1 for t in wanted if t in meal.get(d, [])) / len(wanted)
            ranked.append({**meal, "matchScore": min(1.0, total / 7.0)})
        ranked.sort(key=lambda m: -m["matchScore"])
        ranked = ranked[:_TOP_N]
        return {"count": len(ranked), "meals": ranked}

    async def recent_feedback(self, user_id: int, limit: int | None = None) -> dict[str, Any]:
        self._enter("recent_feedback", (user_id, limit))
        effective = 10 if limit is None else limit
        if not 1 <= effective <= 50:
            raise ToolError("backend returned HTTP 400: limit must be between 1 and 50")
        items = [f for f in self.feedback if f.get("userId") == user_id][:effective]
        return {"userId": user_id, "count": len(items), "items": items}

    async def risk_check(self, text: str) -> dict[str, Any]:
        self._enter("risk_check", text)
        return risk_result(text)

    async def write_trace(self, payload: dict[str, Any]) -> dict[str, Any]:
        self._enter("write_trace", payload)
        if any(t["traceId"] == payload["traceId"] for t in self.traces):
            raise ToolError(f"backend returned HTTP 400: traceId already exists: {payload['traceId']}")
        self.traces.append(payload)
        return {"traceId": payload["traceId"], "eventCount": len(payload.get("events", []))}
