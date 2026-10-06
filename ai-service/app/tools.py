"""The four tools the agent may call, as OpenAI tool definitions plus an executor.

A tool definition is the "menu card" the model reads: a name, when to use it, and the shape of its arguments.
The model only proposes calls; this module validates and runs them. Identity (user id, which library to search,
meals already recommended) comes from RunContext set by the caller - never from model-supplied arguments.
"""

import json
import time
from dataclasses import dataclass, field
from typing import Any, Protocol

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.backend import ToolError
from app.nutrition import NutritionReference
from app.schemas import ALLERGEN_TOKENS, Constraints, Meal, Recommendation
from app.verify import verify

_HARD_EMPTY_NOTE = ("No meal satisfies the hard constraints (max_price / exclude_allergens) on their own, so no tag filter can help. "
                   "Stop searching and answer with meal_id null, naming the constraint that ruled everything out.")

_SLOT_DIMENSIONS = ["meal_time", "mood", "scene", "health_goal", "cuisine", "taste", "convenience"]
_CAMEL = {"meal_time": "mealTime", "health_goal": "healthGoal"}


class Backend(Protocol):
    async def search_meals(self, body: dict[str, Any]) -> dict[str, Any]: ...
    async def recent_feedback(self, user_id: int, limit: int | None = None) -> dict[str, Any]: ...
    async def risk_check(self, text: str) -> dict[str, Any]: ...
    async def slot_options(self) -> dict[str, list[str]]: ...


@dataclass
class RunContext:
    """Per-run state owned by the caller, not by the model."""

    user_id: int
    source_mode: str = "PUBLIC"
    exclude_meal_ids: list[int] = field(default_factory=list)
    candidates: dict[int, Meal] = field(default_factory=dict)  # every meal search_meals returned in this run
    max_price: float | None = None                              # tightest budget used in any search
    exclude_allergens: set[str] = field(default_factory=set)    # union of allergens excluded in any search
    # (max_price, allergens) of searches that used NO tag filter and still found nothing. Any later search with the
    # same or tighter hard constraints is guaranteed to find nothing too, whatever tags it adds.
    empty_hard_searches: list[tuple[float | None, frozenset[str]]] = field(default_factory=list)

    def known_empty(self, max_price: float | None, allergens: set[str]) -> bool:
        for prior_price, prior_allergens in self.empty_hard_searches:
            budget_not_looser = prior_price is None or (max_price is not None and max_price <= prior_price)
            if budget_not_looser and allergens >= prior_allergens:
                return True
        return False

    def constraints(self) -> Constraints:
        return Constraints(max_price=self.max_price, exclude_allergens=sorted(self.exclude_allergens))

    def to_dict(self) -> dict[str, Any]:
        """Plain JSON-able snapshot, so a paused run can be saved and rebuilt in another process."""
        return {"user_id": self.user_id, "source_mode": self.source_mode, "exclude_meal_ids": list(self.exclude_meal_ids),
                "candidates": [m.model_dump(by_alias=True) for m in self.candidates.values()],
                "max_price": self.max_price, "exclude_allergens": sorted(self.exclude_allergens),
                "empty_hard_searches": [[price, sorted(allergens)] for price, allergens in self.empty_hard_searches]}

    def load(self, data: dict[str, Any]) -> None:
        """Replace this context's content with a snapshot made by to_dict (in place: the tools hold this object)."""
        self.user_id = data["user_id"]
        self.source_mode = data["source_mode"]
        self.exclude_meal_ids = list(data["exclude_meal_ids"])
        self.candidates = {m["id"]: Meal.model_validate(m) for m in data["candidates"]}
        self.max_price = data["max_price"]
        self.exclude_allergens = set(data["exclude_allergens"])
        self.empty_hard_searches = [(price, frozenset(allergens)) for price, allergens in data["empty_hard_searches"]]


@dataclass
class ToolOutcome:
    name: str
    content: str        # JSON text that goes back to the model as the tool result
    is_error: bool
    duration_ms: int
    summary: str        # one short line for the trace


class SearchArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")

    meal_time: list[str] = []
    mood: list[str] = []
    scene: list[str] = []
    health_goal: list[str] = []
    cuisine: list[str] = []
    taste: list[str] = []
    convenience: list[str] = []
    max_price: float | None = Field(default=None, ge=0)
    exclude_allergens: list[str] = []


class FeedbackArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")

    limit: int = Field(default=10, ge=1, le=50)


class NutritionArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")

    topic: str = Field(min_length=1)


class VerifyArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")

    recommendation: Recommendation


def _inline_refs(schema: dict[str, Any]) -> dict[str, Any]:
    """Resolve $ref / $defs so the schema is self-contained (simpler for the API than references)."""
    defs = schema.get("$defs", {})

    def resolve(node: Any) -> Any:
        if isinstance(node, dict):
            if "$ref" in node:
                return resolve(defs[node["$ref"].split("/")[-1]])
            return {k: resolve(v) for k, v in node.items() if k != "$defs"}
        if isinstance(node, list):
            return [resolve(v) for v in node]
        return node

    return resolve(schema)


def _compact(meal: Meal) -> dict[str, Any]:
    return {
        "id": meal.id, "name": meal.name, "price": meal.price, "protein_g": meal.protein_g,
        "calories": meal.calories, "allergens": meal.allergens,
        "tags": {"meal_time": meal.meal_time, "mood": meal.mood, "scene": meal.scene,
                 "health_goal": meal.health_goal, "cuisine": meal.cuisine, "taste": meal.taste,
                 "convenience": meal.convenience},
    }


class AgentTools:
    def __init__(self, backend: Backend, nutrition: NutritionReference, context: RunContext, *, model_can_verify: bool = False):
        """model_can_verify offers verify_recommendation to the model as a tool. It is off by default: in the step-6
        smoke run the model spent five of its eight rounds calling it (each call repeats the whole draft as arguments,
        and the long arguments were sometimes corrupted), while the run loop already verifies every final answer."""
        self._backend = backend
        self._nutrition = nutrition
        self.context = context
        self._model_can_verify = model_can_verify

    async def definitions(self) -> list[dict[str, Any]]:
        """The tool list sent to the model; tag arguments are restricted to the backend's real vocabulary."""
        options = await self._backend.slot_options()
        search_props: dict[str, Any] = {}
        for dim in _SLOT_DIMENSIONS:
            values = options.get(_CAMEL.get(dim, dim), [])
            search_props[dim] = {"type": "array", "items": {"type": "string", "enum": values},
                                 "description": f"Soft preference tags for {dim}; omit if the user did not mention any."}
        search_props["max_price"] = {
            "type": "number", "minimum": 0,
            "description": "HARD budget cap in EUR (inclusive). Set it whenever the user states a budget."}
        search_props["exclude_allergens"] = {
            "type": "array", "items": {"type": "string", "enum": ALLERGEN_TOKENS},
            "description": "HARD constraint: allergens the meal must not contain. Set it whenever the user says they "
                           "are allergic to or must avoid something ('shellfish' covers shrimp, crab, clams, oysters)."}
        tools = [
            _tool("search_meals",
                  "Search the meal library and return up to 10 candidate meals, best match first. Call this BEFORE "
                  "recommending anything: only meals returned here may be recommended. max_price and exclude_allergens "
                  "are hard constraints applied by the database; null fields in a result mean UNKNOWN (never assume "
                  "they are safe or cheap). Tag filters are soft preferences matched by overlap. If count is 0, loosen "
                  "or drop tag filters and try again; never drop exclude_allergens, and never invent a meal.",
                  {"type": "object", "properties": search_props, "additionalProperties": False}),
            _tool("get_recent_feedback",
                  "Return the user's most recent LIKE / DISLIKE reactions to recommended meals (newest first). This is "
                  "NOT what they ate. Use it to avoid recommending meals they disliked.",
                  _inline_refs(FeedbackArgs.model_json_schema())),
            _tool("lookup_nutrition",
                  "Look up general public nutrition guidance (USDA Dietary Guidelines 2025-2030, FDA claim definitions, "
                  "MyPlate). Use it only to support GENERAL nutrition statements. Each passage has a source label; "
                  "cite it exactly as guideline_ref. It says nothing about specific meals in the library.",
                  _inline_refs(NutritionArgs.model_json_schema())),
        ]
        if self._model_can_verify:
            tools.append(
                _tool("verify_recommendation",
                  "Check your DRAFT final answer before you give it. It verifies the chosen meal and every "
                  "FROM_CANDIDATE / FROM_GUIDELINE claim against the data returned by your tools, and runs the safety "
                  "check on the reason. Fix every FAIL issue (or pick another meal) and verify again.",
                  _inline_refs(VerifyArgs.model_json_schema())))
        return tools

    async def execute(self, name: str, arguments_json: str | None) -> ToolOutcome:
        started = time.perf_counter()
        try:
            arguments = json.loads(arguments_json) if arguments_json else {}
            if not isinstance(arguments, dict):
                raise ToolError("arguments must be a JSON object")
            handler = {
                "search_meals": self._search_meals,
                "get_recent_feedback": self._recent_feedback,
                "lookup_nutrition": self._lookup_nutrition,
                "verify_recommendation": self._verify,
            }.get(name)
            if handler is None:
                raise ToolError(f"unknown tool '{name}'")
            payload, summary = await handler(arguments)
            return ToolOutcome(name, json.dumps(payload), False, _elapsed_ms(started), summary)
        except json.JSONDecodeError:
            return _error(name, "arguments are not valid JSON", started)
        except ValidationError as e:
            problems = "; ".join(f"{'.'.join(str(p) for p in err['loc'])}: {err['msg']}" for err in e.errors())
            return _error(name, f"invalid arguments: {problems}", started)
        except ToolError as e:
            return _error(name, str(e), started)

    async def _search_meals(self, arguments: dict[str, Any]) -> tuple[dict[str, Any], str]:
        args = SearchArgs.model_validate(arguments)
        allergens = {a.lower() for a in args.exclude_allergens}
        if self.context.known_empty(args.max_price, allergens):
            self._note_constraints(args)
            return ({"count": 0, "meals": [], "note": _HARD_EMPTY_NOTE}, "0 meals (known empty, backend not called)")
        body: dict[str, Any] = {"sourceMode": self.context.source_mode, "userId": self.context.user_id,
                                "excludeMealIds": self.context.exclude_meal_ids,
                                "maxPrice": args.max_price, "excludeAllergens": args.exclude_allergens}
        for dim in _SLOT_DIMENSIONS:
            body[_CAMEL.get(dim, dim)] = getattr(args, dim)
        result = await self._backend.search_meals(body)
        meals = [Meal.model_validate(m) for m in result.get("meals", [])]
        for meal in meals:
            self.context.candidates[meal.id] = meal
        self._note_constraints(args)
        payload: dict[str, Any] = {"count": len(meals), "meals": [_compact(m) for m in meals]}
        if not meals:
            if any(getattr(args, dim) for dim in _SLOT_DIMENSIONS):
                payload["note"] = ("No meal matched. You may loosen or drop the optional tag filters; max_price and "
                                   "exclude_allergens must stay. Do not invent a meal.")
            else:
                self.context.empty_hard_searches.append((args.max_price, frozenset(allergens)))
                payload["note"] = _HARD_EMPTY_NOTE
        return payload, f"{len(meals)} meals"

    def _note_constraints(self, args: SearchArgs) -> None:
        """Track the tightest constraints used in the run: lowest budget, union of allergens."""
        if args.max_price is not None:
            self.context.max_price = (args.max_price if self.context.max_price is None
                                      else min(self.context.max_price, args.max_price))
        self.context.exclude_allergens |= {a.lower() for a in args.exclude_allergens}

    async def _recent_feedback(self, arguments: dict[str, Any]) -> tuple[dict[str, Any], str]:
        args = FeedbackArgs.model_validate(arguments)
        result = await self._backend.recent_feedback(self.context.user_id, args.limit)
        return result, f"{result.get('count', 0)} reactions"

    async def _lookup_nutrition(self, arguments: dict[str, Any]) -> tuple[dict[str, Any], str]:
        args = NutritionArgs.model_validate(arguments)
        passages = self._nutrition.lookup(args.topic)
        payload: dict[str, Any] = {"passages": [{"source": p.source, "text": p.text, "caveat": p.caveat} for p in passages]}
        if not passages:
            payload["note"] = "No passage matched this topic. Do not make FROM_GUIDELINE claims about it."
        return payload, f"{len(passages)} passages"

    async def _verify(self, arguments: dict[str, Any]) -> tuple[dict[str, Any], str]:
        args = VerifyArgs.model_validate(arguments)
        report = verify(args.recommendation, self.context.candidates, self.context.constraints(),
                        self._nutrition.source_labels())
        risk: dict[str, Any]
        try:
            risk = await self._backend.risk_check(args.recommendation.reason)
        except ToolError as e:
            # Fail closed: if the safety check cannot run, the draft is not verified.
            risk = {"blocked": True, "reasons": [f"risk check unavailable: {e}"]}
        payload = report.to_dict()
        if risk.get("blocked"):
            payload["ok"] = False
            payload["issues"].append({"code": "RISK_BLOCKED", "severity": "FAIL", "claim_index": None,
                                      "message": "; ".join(risk.get("reasons", [])) or "blocked by the risk check"})
        payload["constraints_checked"] = self.context.constraints().model_dump()
        return payload, "ok" if payload["ok"] else f"{sum(1 for i in payload['issues'] if i['severity'] == 'FAIL')} FAIL"


def _tool(name: str, description: str, parameters: dict[str, Any]) -> dict[str, Any]:
    return {"type": "function", "function": {"name": name, "description": description, "parameters": parameters}}


def _elapsed_ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)


def _error(name: str, message: str, started: float) -> ToolOutcome:
    return ToolOutcome(name, json.dumps({"error": message}), True, _elapsed_ms(started), f"error: {message[:80]}")
