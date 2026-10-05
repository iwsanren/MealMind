"""Evaluation harness: ground truth, three arms (A baseline, B baseline + verification, C agent), scoring, summary.

Everything is deterministic except the model. All scoring uses the TRUE constraints of each case, never the
constraints the system extracted for itself. All data is synthetic (see build_dataset.py).

Arms (same model, temperature 0, same facts, same output schema):
  A  one model call, the WHOLE menu (with facts) in the prompt, no tools, no verification        (a strong, simple baseline)
  B  A + code verification with ORACLE constraints + up to 2 corrections                           (an upper bound for "verification only")
  C  the agent: tools (hard-constraint search in the backend, feedback, nutrition) + enforced verification
"""

import asyncio
import json
import math
import random
import time
from collections import deque
from dataclasses import dataclass
from types import SimpleNamespace
from pathlib import Path
from typing import Any

import openai
from pydantic import ValidationError

from app.agent import AgentRun, Status, response_format, run_agent
from app.fake_backend import FakeBackend, risk_result
from app.nutrition import NutritionReference
from app.prompts import load_agent_prompt, load_baseline_prompt
from app.schemas import ClaimField, ClaimSource, Constraints, Meal, Recommendation
from app.spend import BudgetExceeded, SpendTracker, cost_usd
from app.tools import AgentTools, RunContext, _compact as compact_meal
from app.verify import HIGH_PROTEIN_G_PER_SERVING, Severity, verify

HERE = Path(__file__).resolve().parent
ARMS = ("A", "B", "C")
ARM_NAMES = {"A": "baseline (one call, whole menu)", "B": "baseline + verification (oracle constraints)", "C": "agent (tools + verification)"}


# ---------------------------------------------------------------- data

def load_catalog() -> list[dict[str, Any]]:
    return json.loads((HERE / "catalog.json").read_text(encoding="utf-8"))


def load_cases() -> list[dict[str, Any]]:
    return json.loads((HERE / "cases.json").read_text(encoding="utf-8"))


def load_slot_options() -> dict[str, list[str]]:
    return json.loads((HERE / "slot_options.json").read_text(encoding="utf-8"))


def feasible_meal_ids(case: dict[str, Any], catalog: list[dict[str, Any]]) -> list[int]:
    """Meals that truly satisfy the case. Unknown (null) facts never satisfy a constraint that is set."""
    out = []
    for meal in catalog:
        if case["max_price"] is not None and (meal["price"] is None or meal["price"] > case["max_price"]):
            continue
        if case["exclude_allergens"]:
            if meal["allergens"] is None or set(case["exclude_allergens"]) & set(meal["allergens"]):
                continue
        if case["requires_high_protein"] and (meal["proteinG"] is None or meal["proteinG"] < HIGH_PROTEIN_G_PER_SERVING):
            continue
        if case["min_protein_g"] is not None and (meal["proteinG"] is None or meal["proteinG"] < case["min_protein_g"]):
            continue
        out.append(meal["id"])
    return out


def guard_blocks(text: str) -> tuple[bool, list[str]]:
    """The backend's RiskGuard rules (ported in FakeBackend) applied to a piece of text."""
    result = risk_result(text)
    return result["blocked"], result["reasons"]


# ---------------------------------------------------------------- results

@dataclass
class RunResult:
    arm: str
    case_id: str
    repeat: int
    status: str                       # SUCCESS | UNVERIFIED | PARSE_FAILED | MAX_ROUNDS | ERROR
    recommendation: dict | None
    raw_final: str | None
    llm_calls: int
    prompt_tokens: int
    completion_tokens: int
    cost_usd: float
    duration_ms: int
    error: str | None = None
    prompt_version: str = ""
    events: list[dict] | None = None  # only the agent arm keeps its trace

    @property
    def shown(self) -> bool:
        """An answer is shown to the user only if it was produced and (where the arm verifies) passed verification."""
        return self.status == "SUCCESS" and self.recommendation is not None


def _recommendation_or_none(text: str | None) -> Recommendation | None:
    try:
        return Recommendation.model_validate_json(text or "")
    except ValidationError:
        return None


# ---------------------------------------------------------------- scoring

def score_run(case: dict[str, Any], result: RunResult, catalog_by_id: dict[int, dict[str, Any]],
              known_sources: set[str]) -> dict[str, Any]:
    """Boolean/numeric outcome flags for one run, from the true constraints of the case."""
    feasible = set(feasible_meal_ids(case, list(catalog_by_id.values())))
    rec = _recommendation_or_none(json.dumps(result.recommendation)) if result.recommendation else None
    shown = result.shown and rec is not None
    flags: dict[str, Any] = {
        "shown": shown, "feasible_exists": bool(feasible), "recommended_id": None, "no_match": False,
        "hallucinated_dish": False, "hard_violation": False, "claim_fail": False, "claim_fail_count": 0,
        "unverifiable_claims": 0, "checkable_claims": 0, "unsafe": False, "false_no_match": False,
        "correct_outcome": False, "disliked_shown": False, "off_meal_time": False, "reason_risk_flag": False, "issues": [],
    }
    if not shown:
        return flags

    flags["reason_risk_flag"] = guard_blocks(rec.reason)[0]
    if rec.meal_id is None:
        flags["no_match"] = True
        flags["false_no_match"] = bool(feasible)
        flags["correct_outcome"] = not feasible
        return flags

    flags["recommended_id"] = rec.meal_id
    meal = catalog_by_id.get(rec.meal_id)
    if meal is None or (rec.meal_name or "").strip().casefold() != meal["name"].casefold():
        flags["hallucinated_dish"] = True
    if meal is not None:
        flags["hard_violation"] = rec.meal_id not in feasible
        flags["disliked_shown"] = rec.meal_id in case["disliked_meal_ids"]
        flags["off_meal_time"] = bool(case.get("meal_time")) and case["meal_time"] not in meal["mealTime"]
        pool ={meal["id"]: Meal.model_validate(meal)}
        report = verify(rec, pool, Constraints(max_price=case["max_price"], exclude_allergens=case["exclude_allergens"]),
                        known_sources)
        claim_fail_codes = [i.code for i in report.issues if i.severity is Severity.FAIL
                            and (i.claim_index is not None or i.code == "REASON_NUMBER_UNSUPPORTED")]
        # The user's own budget must be quoted correctly too: "within your $20 budget" is false if they said 15.
        for claim in rec.claims:
            if claim.field is ClaimField.WITHIN_BUDGET and case["max_price"] is not None and claim.number is not None \
                    and abs(claim.number - case["max_price"]) > 0.005:
                claim_fail_codes.append("BUDGET_MISSTATED")
        flags["claim_fail_count"] = len(claim_fail_codes)
        flags["claim_fail"] = bool(claim_fail_codes)
        flags["unverifiable_claims"] = sum(1 for i in report.issues if i.severity is Severity.UNVERIFIABLE)
        flags["issues"] = sorted(set(claim_fail_codes))
    flags["checkable_claims"] = sum(1 for c in rec.claims if c.source is ClaimSource.FROM_CANDIDATE and c.field is not ClaimField.OTHER)
    flags["unsafe"] = flags["hard_violation"] or flags["claim_fail"] or flags["hallucinated_dish"]
    flags["false_no_match"] = False
    flags["correct_outcome"] = (meal is not None and rec.meal_id in feasible and not flags["unsafe"])
    return flags


# ---------------------------------------------------------------- arms

def render_menu(catalog: list[dict[str, Any]]) -> str:
    return json.dumps([compact_meal(Meal.model_validate(m)) for m in catalog], separators=(",", ":"))


def baseline_user_message(case: dict[str, Any], catalog: list[dict[str, Any]], menu_json: str) -> str:
    names = {m["id"]: m["name"] for m in catalog}
    disliked = case["disliked_meal_ids"]
    feedback = ("The user previously DISLIKED: " + "; ".join(f"{names[i]} (id {i})" for i in disliked) + "."
                if disliked else "No feedback history.")
    return f"MENU (every meal you may recommend; null means UNKNOWN):\n{menu_json}\n\n{feedback}\n\nUSER MESSAGE:\n{case['user_message']}"


async def _verify_oracle(rec: Recommendation, case, pool, known_sources, backend) -> tuple[bool, str]:
    """Verification with the case's TRUE budget and allergens, plus the same risk check the agent uses."""
    report = verify(rec, pool, Constraints(max_price=case["max_price"], exclude_allergens=case["exclude_allergens"]), known_sources)
    ok, lines = report.ok, [f"[{i.severity.value}] {i.code}: {i.message}" for i in report.issues]
    risk = await backend.risk_check(rec.reason)
    if risk["blocked"]:
        ok = False
        lines.append("[FAIL] RISK_BLOCKED: " + "; ".join(risk["reasons"]))
    return ok, "; ".join(lines)


async def run_baseline_arm(arm: str, client: Any, case: dict[str, Any], catalog: list[dict[str, Any]], menu_json: str, *,
                           model: str, temperature: float, spend: SpendTracker, prompt_version: str, known_sources: set[str],
                           max_format_retries: int = 2, max_verify_retries: int = 2, repeat: int = 0) -> RunResult:
    """Arm A (verify=False) and arm B (verify=True): one call, then corrections."""
    started = time.perf_counter()
    verify_on = arm == "B"
    backend = FakeBackend(catalog)
    pool = {m["id"]: Meal.model_validate(m) for m in catalog}
    messages = [{"role": "system", "content": load_baseline_prompt(prompt_version)},
                {"role": "user", "content": baseline_user_message(case, catalog, menu_json)}]
    calls = prompt_tokens = completion_tokens = 0
    cost = 0.0
    format_retries = verify_retries = 0
    status, recommendation, raw, error = "ERROR", None, None, None

    def result() -> RunResult:
        return RunResult(arm, case["id"], repeat, status, recommendation, raw, calls, prompt_tokens, completion_tokens,
                         round(cost, 6), int((time.perf_counter() - started) * 1000), error, prompt_version)

    while True:
        try:
            spend.check()
            response = await client.chat.completions.create(model=model, temperature=temperature, messages=messages,
                                                            response_format=response_format())
        except BudgetExceeded as e:
            error = str(e)
            return result()
        except Exception as e:  # network, auth, rate limit: the run ends with an error status, the evaluation goes on
            error = f"LLM call failed: {type(e).__name__}"
            return result()
        calls += 1
        prompt_tokens += response.usage.prompt_tokens
        completion_tokens += response.usage.completion_tokens
        cost += spend.add(model, response.usage.prompt_tokens, response.usage.completion_tokens)
        raw = response.choices[0].message.content or ""

        rec = _recommendation_or_none(raw)
        if rec is None:
            if format_retries >= max_format_retries:
                status, error = "PARSE_FAILED", "final answer was not valid JSON for the schema"
                return result()
            format_retries += 1
            messages += [{"role": "assistant", "content": raw},
                         {"role": "user", "content": "Your answer was not valid. Reply again with ONLY the corrected JSON object."}]
            continue
        recommendation = rec.model_dump(mode="json")
        if not verify_on:
            status = "SUCCESS"
            return result()
        ok, issue_text = await _verify_oracle(rec, case, pool, known_sources, backend)
        if ok:
            status = "SUCCESS"
            return result()
        if verify_retries >= max_verify_retries:
            status, error = "UNVERIFIED", f"still failed verification: {issue_text}"
            return result()
        verify_retries += 1
        messages += [{"role": "assistant", "content": raw},
                     {"role": "user", "content": f"Verification failed: {issue_text}. Fix these problems, choose another meal, "
                                                 "or answer with meal_id null, and reply with ONLY the JSON object."}]


async def run_agent_arm(client: Any, case: dict[str, Any], catalog: list[dict[str, Any]], slot_options: dict[str, list[str]],
                        nutrition: NutritionReference, *, model: str, temperature: float, spend: SpendTracker,
                        prompt_version: str, repeat: int = 0) -> RunResult:
    names = {m["id"]: m["name"] for m in catalog}
    feedback = [{"userId": case["user_id"], "mealId": i, "mealName": names[i], "action": "DISLIKE",
                 "createdAt": "2026-10-01T12:00:00"} for i in case["disliked_meal_ids"]]
    backend = FakeBackend(catalog, slot_options=slot_options, feedback=feedback)
    tools = AgentTools(backend, nutrition, RunContext(user_id=case["user_id"]))
    run: AgentRun = await run_agent(client, tools, case["user_message"], model=model, temperature=temperature,
                                    system_prompt=load_agent_prompt(prompt_version), prompt_version=prompt_version, spend=spend)
    return RunResult("C", case["id"], repeat, run.status.value,
                     run.recommendation.model_dump(mode="json") if run.recommendation else None, run.raw_final,
                     run.usage.llm_calls, run.usage.prompt_tokens, run.usage.completion_tokens, round(run.usage.cost_usd, 6),
                     run.duration_ms, run.error, prompt_version, run.events)


# ---------------------------------------------------------------- statistics

def wilson(k: int, n: int, z: float = 1.96) -> tuple[float, float]:
    """95% Wilson score interval for a proportion. Repeats of the same case are not independent, so read it as a guide."""
    if n == 0:
        return (0.0, 0.0)
    p = k / n
    denom = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / denom
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom
    return (max(0.0, centre - half), min(1.0, centre + half))


def _percentile(values: list[float], q: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    index = min(len(ordered) - 1, max(0, int(round(q * (len(ordered) - 1)))))
    return ordered[index]


def _rate(k: int, n: int) -> dict[str, Any]:
    lo, hi = wilson(k, n)
    return {"k": k, "n": n, "rate": (k / n if n else 0.0), "ci95": [round(lo, 4), round(hi, 4)]}


def summarize(rows: list[dict[str, Any]], seed: int = 1234) -> dict[str, Any]:
    """rows: one dict per run with the RunResult fields plus a 'score' dict. Returns the numbers the report is built from."""
    summary: dict[str, Any] = {"arms": {}, "paired": {}, "by_category": {}}
    by_arm: dict[str, list[dict[str, Any]]] = {}
    for row in rows:
        by_arm.setdefault(row["arm"], []).append(row)

    for arm, items in sorted(by_arm.items()):
        n = len(items)
        s = [r["score"] for r in items]
        recommended = [x for x in s if x["recommended_id"] is not None]
        latencies = [r["duration_ms"] for r in items]
        summary["arms"][arm] = {
            "name": ARM_NAMES.get(arm, arm), "runs": n,
            "shown": _rate(sum(x["shown"] for x in s), n),
            "no_answer": _rate(sum(not x["shown"] for x in s), n),
            "unsafe": _rate(sum(x["unsafe"] for x in s), n),
            "hard_violation": _rate(sum(x["hard_violation"] for x in s), n),
            "claim_fail": _rate(sum(x["claim_fail"] for x in s), n),
            "hallucinated_dish": _rate(sum(x["hallucinated_dish"] for x in s), n),
            "false_no_match": _rate(sum(x["false_no_match"] for x in s), n),
            "correct_outcome": _rate(sum(x["correct_outcome"] for x in s), n),
            "disliked_shown": _rate(sum(x["disliked_shown"] for x in s), n),
            "off_meal_time_of_recommended": _rate(sum(x["off_meal_time"] for x in recommended), len(recommended)),
            "claims_failed_of_checkable": {"failed": sum(x["claim_fail_count"] for x in s),
                                           "checkable": sum(x["checkable_claims"] for x in s)},
            "recommended_runs": len(recommended),
            "avg_llm_calls": sum(r["llm_calls"] for r in items) / n,
            "avg_prompt_tokens": sum(r["prompt_tokens"] for r in items) / n,
            "avg_completion_tokens": sum(r["completion_tokens"] for r in items) / n,
            "avg_cost_usd": sum(r["cost_usd"] for r in items) / n,
            "total_cost_usd": sum(r["cost_usd"] for r in items),
            "latency_ms_p50": _percentile(latencies, 0.5), "latency_ms_p95": _percentile(latencies, 0.95),
            "statuses": {st: sum(1 for r in items if r["status"] == st) for st in sorted({r["status"] for r in items})},
        }
        cats: dict[str, list[dict[str, Any]]] = {}
        for r in items:
            cats.setdefault(r["category"], []).append(r["score"])
        summary["by_category"][arm] = {c: {"runs": len(v), "unsafe": sum(x["unsafe"] for x in v),
                                           "correct_outcome": sum(x["correct_outcome"] for x in v)} for c, v in sorted(cats.items())}

    # Paired comparison per case: the mean over repeats of "unsafe" / "correct", for each arm.
    def per_case(arm: str, key: str) -> dict[str, float]:
        grouped: dict[str, list[float]] = {}
        for r in by_arm.get(arm, []):
            grouped.setdefault(r["case_id"], []).append(1.0 if r["score"][key] else 0.0)
        return {c: sum(v) / len(v) for c, v in grouped.items()}

    rng = random.Random(seed)
    for left, right in (("A", "C"), ("A", "B"), ("B", "C")):
        if left not in by_arm or right not in by_arm:
            continue
        for key in ("unsafe", "correct_outcome"):
            a, b = per_case(left, key), per_case(right, key)
            shared = sorted(set(a) & set(b))
            diffs = [a[c] - b[c] for c in shared]  # positive = the right-hand arm is better for "unsafe", worse for "correct"
            boots = []
            for _ in range(2000):
                sample = [diffs[rng.randrange(len(diffs))] for _ in diffs] if diffs else [0.0]
                boots.append(sum(sample) / len(sample))
            summary["paired"][f"{left}-{right}:{key}"] = {
                "cases": len(shared), "mean_diff": (sum(diffs) / len(diffs) if diffs else 0.0),
                "bootstrap_ci95": [round(_percentile(boots, 0.025), 4), round(_percentile(boots, 0.975), 4)],
                "left_higher": sum(1 for d in diffs if d > 1e-9), "right_higher": sum(1 for d in diffs if d < -1e-9),
                "equal": sum(1 for d in diffs if abs(d) <= 1e-9)}
    return summary


def guard_report(cases: list[dict[str, Any]]) -> dict[str, Any]:
    """How the RiskGuard gate treats the evaluation messages (it runs before any arm)."""
    rows = []
    for c in cases:
        blocked, reasons = guard_blocks(c["user_message"])
        rows.append({"case_id": c["id"], "category": c["category"], "blocked": blocked, "reasons": reasons})
    expected_block = {"medical"}
    return {
        "blocked_case_ids": [r["case_id"] for r in rows if r["blocked"]],
        "medical_blocked": f"{sum(r['blocked'] for r in rows if r['category'] == 'medical')}/{sum(r['category'] == 'medical' for r in rows)}",
        "false_positives": [r["case_id"] for r in rows if r["blocked"] and r["category"] not in expected_block],
        "false_negatives": [r["case_id"] for r in rows if not r["blocked"] and r["category"] in expected_block],
        "benign_treat_blocked": f"{sum(r['blocked'] for r in rows if r['category'] == 'benign_treat')}/{sum(r['category'] == 'benign_treat' for r in rows)}",
        "rows": rows,
    }


# ---------------------------------------------------------------- cost estimate

def estimate_cost(arms: list[str], n_cases: int, repeats: int, model: str, menu_chars: int) -> dict[str, Any]:
    """Rough planning numbers (not a guarantee): arm A is one call over the menu; B up to ~1.4 calls; C uses the average
    measured in step 4 (about 3.5 calls, 8k input / 0.5k output tokens per run)."""
    menu_tokens = menu_chars // 4 + 900  # characters / 4, plus the system prompt and the question
    per_run = {"A": (menu_tokens, 350), "B": (int(menu_tokens * 1.4), 450), "C": (8000, 500)}
    total = 0.0
    detail = {}
    for arm in arms:
        p, c = per_run[arm]
        cost = n_cases * repeats * cost_usd(model, p, c)
        detail[arm] = {"runs": n_cases * repeats, "input_tokens_per_run": p, "output_tokens_per_run": c, "estimated_usd": round(cost, 4)}
        total += cost
    return {"model": model, "arms": detail, "estimated_total_usd": round(total, 4)}


# ---------------------------------------------------------------- pacing (API rate limits are not model behaviour)

class TokenPacer:
    """Keeps the tokens sent in any rolling 60 seconds under a budget, using an estimate of each request's size."""

    def __init__(self, tokens_per_minute: int):
        self.limit = tokens_per_minute
        self._window: deque[tuple[float, int]] = deque()
        self._lock = asyncio.Lock()

    async def acquire(self, estimated_tokens: int) -> None:
        while True:
            async with self._lock:
                now = time.monotonic()
                while self._window and now - self._window[0][0] >= 60:
                    self._window.popleft()
                if not self._window or sum(t for _, t in self._window) + estimated_tokens <= self.limit:
                    self._window.append((now, estimated_tokens))
                    return
                wait = 60 - (now - self._window[0][0])
            await asyncio.sleep(max(0.2, wait))


def estimate_request_tokens(kwargs: dict[str, Any]) -> int:
    text = sum(len(m.get("content") or "") for m in kwargs.get("messages", []))
    tools = len(json.dumps(kwargs.get("tools") or []))
    return (text + tools) // 3 + 600  # deliberately generous: characters / 3, plus room for the answer


class PacedClient:
    """Drop-in for the OpenAI client's chat.completions.create: paced, and retried on 429 with backoff."""

    def __init__(self, client: Any, tokens_per_minute: int, max_retries: int = 6, base_backoff_s: float = 5.0):
        self._client = client
        self._pacer = TokenPacer(tokens_per_minute)
        self._max_retries = max_retries
        self._base_backoff_s = base_backoff_s
        self.rate_limit_retries = 0
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self._create))

    async def _create(self, **kwargs: Any) -> Any:
        for attempt in range(self._max_retries + 1):
            await self._pacer.acquire(estimate_request_tokens(kwargs))
            try:
                return await self._client.chat.completions.create(**kwargs)
            except openai.RateLimitError:
                if attempt == self._max_retries:
                    raise
                self.rate_limit_retries += 1
                await asyncio.sleep(self._base_backoff_s * (2 ** attempt))

    async def close(self) -> None:
        await self._client.close()


def is_infrastructure_failure(result: "RunResult") -> bool:
    """A run that never got an answer because the API call itself failed says nothing about the system under test."""
    return result.status == "ERROR" and (result.error or "").startswith("LLM call failed")


# ---------------------------------------------------------------- orchestration

async def run_evaluation(client: Any, *, arms: list[str], cases: list[dict[str, Any]], catalog: list[dict[str, Any]],
                         slot_options: dict[str, list[str]], nutrition: NutritionReference, repeats: int, model: str,
                         temperature: float, spend: SpendTracker, agent_prompt_version: str, baseline_prompt_version: str,
                         concurrency: int = 6, on_result=None, infrastructure_retries: int = 2,
                         infrastructure_retry_wait_s: float = 15.0) -> list[RunResult]:
    menu_json = render_menu(catalog)
    known_sources = nutrition.source_labels()
    semaphore = asyncio.Semaphore(concurrency)
    results: list[RunResult] = []

    async def one(arm: str, case: dict[str, Any], repeat: int) -> None:
        async with semaphore:
            for attempt in range(1 + infrastructure_retries):
                if arm == "C":
                    r = await run_agent_arm(client, case, catalog, slot_options, nutrition, model=model, temperature=temperature,
                                            spend=spend, prompt_version=agent_prompt_version, repeat=repeat)
                else:
                    r = await run_baseline_arm(arm, client, case, catalog, menu_json, model=model, temperature=temperature, spend=spend,
                                               prompt_version=baseline_prompt_version, known_sources=known_sources, repeat=repeat)
                if not is_infrastructure_failure(r):
                    break
                await asyncio.sleep(infrastructure_retry_wait_s)   # the API failed, not the system: try this run again
            results.append(r)
            if on_result is not None:
                on_result(r)

    await asyncio.gather(*(one(arm, case, rep) for arm in arms for case in cases for rep in range(repeats)))
    return results


# ---------------------------------------------------------------- report

def _pct(x: dict[str, Any]) -> str:
    return f"{100 * x['rate']:.1f}% ({x['k']}/{x['n']})"


def render_markdown(summary: dict[str, Any], guard: dict[str, Any], meta: dict[str, Any]) -> str:
    arms = summary["arms"]
    order = [a for a in ARMS if a in arms]
    lines = [
        f"# Evaluation results ({meta['stamp']})", "",
        f"Model `{meta['model']}` (temperature {meta['temperature']}); agent prompt `{meta['agent_prompt']}`, baseline prompt "
        f"`{meta['baseline_prompt']}`. {meta['n_cases']} cases entered the comparison, {meta['repeats']} repeats each. "
        f"Real spend: ${meta['spent_usd']:.4f}.", "",
        "> **All data is synthetic.** The 40-meal catalog and the 40 cases were written to contain edge cases (budget and "
        "protein boundaries, unknown facts, mislabeled tags, multi-allergen dishes); they say how each arm handles those "
        "cases, not how accurate any arm is on real menus. Repeats of one case are not independent, so intervals are a guide.", "",
        "## Arms", ""] + [f"- **{a}** - {arms[a]['name']}" for a in order] + ["",
        "## Headline (per run; lower is better for the first row, higher for the others)", "",
        "| metric | " + " | ".join(order) + " |", "|---|" + "---|" * len(order)]
    rows = [("unsafe answer (any unsupported claim, hard-constraint violation or invented dish)", "unsafe"),
            ("  of which hard-constraint violation (budget / allergen / protein need)", "hard_violation"),
            ("  of which unsupported claim", "claim_fail"),
            ("  of which invented dish", "hallucinated_dish"),
            ("said 'nothing fits' when something did", "false_no_match"),
            ("**correct outcome** (safe, and the right kind of answer)", "correct_outcome"),
            ("no answer shown (parse failure, withheld, error)", "no_answer"),
            ("recommended a disliked meal", "disliked_shown")]
    for label, key in rows:
        lines.append(f"| {label} | " + " | ".join(_pct(arms[a][key]) for a in order) + " |")
    lines.append("| off the requested meal time (of runs that recommended a meal) | " +
                 " | ".join(_pct(arms[a]["off_meal_time_of_recommended"]) for a in order) + " |")
    lines += ["", "95% Wilson intervals (unsafe / correct):", ""]
    for a in order:
        u, c = arms[a]["unsafe"], arms[a]["correct_outcome"]
        lines.append(f"- {a}: unsafe {100 * u['ci95'][0]:.1f}-{100 * u['ci95'][1]:.1f}%, correct {100 * c['ci95'][0]:.1f}-{100 * c['ci95'][1]:.1f}%")
    lines += ["", "## Cost and speed", "", "| | " + " | ".join(order) + " |", "|---|" + "---|" * len(order),
              "| model calls per run | " + " | ".join(f"{arms[a]['avg_llm_calls']:.2f}" for a in order) + " |",
              "| input / output tokens per run | " + " | ".join(f"{arms[a]['avg_prompt_tokens']:.0f} / {arms[a]['avg_completion_tokens']:.0f}" for a in order) + " |",
              "| USD per run | " + " | ".join(f"${arms[a]['avg_cost_usd']:.5f}" for a in order) + " |",
              "| latency p50 / p95 | " + " | ".join(f"{arms[a]['latency_ms_p50'] / 1000:.1f}s / {arms[a]['latency_ms_p95'] / 1000:.1f}s" for a in order) + " |",
              "", "Statuses: " + "; ".join(f"{a}: {arms[a]['statuses']}" for a in order), "",
              "## Paired by case (mean over repeats; positive = the left arm is worse on 'unsafe' / better on 'correct')", ""]
    for key, p in summary["paired"].items():
        lines.append(f"- {key}: mean difference {p['mean_diff']:+.3f} (bootstrap 95% CI {p['bootstrap_ci95'][0]:+.3f} to "
                     f"{p['bootstrap_ci95'][1]:+.3f}) over {p['cases']} cases; left higher in {p['left_higher']}, right higher in {p['right_higher']}, equal in {p['equal']}")
    lines += ["", "## By category (unsafe runs / correct runs, of runs in that category)", "",
              "| category | " + " | ".join(f"{a} unsafe | {a} correct" for a in order) + " |", "|---|" + "---|---|" * len(order)]
    cats = sorted({c for a in order for c in summary["by_category"][a]})
    for c in cats:
        cells = []
        for a in order:
            v = summary["by_category"][a].get(c)
            cells += [f"{v['unsafe']}/{v['runs']}", f"{v['correct_outcome']}/{v['runs']}"] if v else ["-", "-"]
        lines.append(f"| {c} | " + " | ".join(cells) + " |")
    lines += ["", "## RiskGuard gate (runs before any arm; the same for every arm)", "",
              f"- medical messages stopped: {guard['medical_blocked']}",
              f"- benign messages containing the word 'treat' stopped (false positives): {guard['benign_treat_blocked']}",
              f"- cases kept out of the comparison because the gate stopped them: {', '.join(guard['blocked_case_ids']) or 'none'}", ""]
    return "\n".join(lines)
