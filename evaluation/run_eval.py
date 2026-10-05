"""Run the evaluation.

    # from MealMind/ai-service (its venv has the dependencies):
    .venv\\Scripts\\python ..\\evaluation\\run_eval.py --dry-run                   # plan and estimate, no API calls
    .venv\\Scripts\\python ..\\evaluation\\run_eval.py --arms A,B,C --repeats 3    # the real run
    .venv\\Scripts\\python ..\\evaluation\\run_eval.py --cases h1,b5 --repeats 1   # a small smoke run

Real runs spend money: the estimate is printed first, a run whose estimate exceeds --cap is refused, and the cap is
also enforced call by call. Every run writes runs.jsonl (one line per run, including the raw model answer),
summary.json and summary.md into evaluation/results/<timestamp>/.
"""

import argparse
import asyncio
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "ai-service"))
sys.path.insert(0, str(HERE.parent))

from app.config import get_settings  # noqa: E402
from app.llm import make_client  # noqa: E402
from app.nutrition import NutritionReference  # noqa: E402
from app.spend import SpendTracker  # noqa: E402
from evaluation import harness  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--arms", default="A,B,C")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--cases", default="", help="comma-separated case ids (default: all that pass the RiskGuard gate)")
    parser.add_argument("--include-guarded", action="store_true", help="also run cases the RiskGuard gate would stop")
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--tpm", type=int, default=120000, help="tokens per minute to stay under (the key allows 200000)")
    parser.add_argument("--cap", type=float, default=2.0, help="USD this evaluation may spend")
    parser.add_argument("--model", default=None)
    parser.add_argument("--baseline-prompt", default="v1")
    parser.add_argument("--agent-prompt", default=None)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--out", default=None)
    args = parser.parse_args()

    settings = get_settings()
    model = args.model or settings.agent_model
    agent_prompt = args.agent_prompt or settings.agent_prompt_version
    arms = [a.strip() for a in args.arms.split(",") if a.strip()]
    catalog, cases_all, slot_options = harness.load_catalog(), harness.load_cases(), harness.load_slot_options()
    guard = harness.guard_report(cases_all)
    wanted = {c.strip() for c in args.cases.split(",") if c.strip()}
    cases = [c for c in cases_all
             if (not wanted or c["id"] in wanted) and (args.include_guarded or c["id"] not in guard["blocked_case_ids"])]
    menu_chars = len(harness.render_menu(catalog))
    estimate = harness.estimate_cost(arms, len(cases), args.repeats, model, menu_chars)

    print(f"model={model} agent_prompt={agent_prompt} baseline_prompt={args.baseline_prompt}")
    print(f"{len(cases_all)} cases in the dataset; RiskGuard gate stops {guard['blocked_case_ids']}; "
          f"{len(cases)} cases x {args.repeats} repeats x arms {arms}")
    print(json.dumps(estimate, indent=2))
    if args.dry_run:
        print("dry run: nothing was sent.")
        return
    if estimate["estimated_total_usd"] > args.cap:
        sys.exit(f"refusing to run: estimated ${estimate['estimated_total_usd']} exceeds the cap ${args.cap}")

    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    out = Path(args.out) if args.out else HERE / "results" / stamp
    out.mkdir(parents=True, exist_ok=True)
    spend = SpendTracker(cap_usd=args.cap)
    client = harness.PacedClient(make_client(settings), tokens_per_minute=args.tpm)
    nutrition = NutritionReference.from_file(settings.nutrition_reference_file)
    total = len(arms) * len(cases) * args.repeats
    done = []

    def progress(result) -> None:
        done.append(result)
        if len(done) % 20 == 0 or len(done) == total:
            print(f"  {len(done)}/{total} runs, ${spend.spent_usd:.4f} spent", flush=True)

    async def go():
        try:
            return await harness.run_evaluation(
                client, arms=arms, cases=cases, catalog=catalog, slot_options=slot_options, nutrition=nutrition,
                repeats=args.repeats, model=model, temperature=settings.agent_temperature, spend=spend,
                agent_prompt_version=agent_prompt, baseline_prompt_version=args.baseline_prompt,
                concurrency=args.concurrency, on_result=progress)
        finally:
            await client.close()

    results = asyncio.run(go())
    failed = [r for r in results if harness.is_infrastructure_failure(r)]
    print(f"rate-limit retries inside the client: {client.rate_limit_retries}; runs that still failed for infrastructure reasons: {len(failed)}")

    by_id = {m["id"]: m for m in catalog}
    cases_by_id = {c["id"]: c for c in cases_all}
    known_sources = nutrition.source_labels()
    rows = []
    for r in sorted(results, key=lambda r: (r.arm, r.case_id, r.repeat)):
        case = cases_by_id[r.case_id]
        row = {k: v for k, v in r.__dict__.items()}
        row["category"] = case["category"]
        row["score"] = harness.score_run(case, r, by_id, known_sources)
        rows.append(row)
    (out / "runs.jsonl").write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + "\n", encoding="utf-8")

    summary = harness.summarize(rows)
    meta = {"stamp": stamp, "model": model, "temperature": settings.agent_temperature, "agent_prompt": agent_prompt,
            "baseline_prompt": args.baseline_prompt, "n_cases": len(cases), "repeats": args.repeats, "arms": arms,
            "spent_usd": round(spend.spent_usd, 6), "estimate": estimate, "data": "synthetic (evaluation/build_dataset.py)"}
    (out / "summary.json").write_text(json.dumps({"meta": meta, "summary": summary, "guard": {k: v for k, v in guard.items() if k != "rows"}},
                                                 indent=2), encoding="utf-8")
    (out / "summary.md").write_text(harness.render_markdown(summary, guard, meta), encoding="utf-8")
    if failed:
        (out / "INCOMPLETE.txt").write_text(
            f"{len(failed)} of {len(results)} runs failed at the API level (e.g. rate limits) and are NOT valid observations.\n"
            "Do not quote numbers from this directory.\n", encoding="utf-8")
        print(f"\n!!! INCOMPLETE: {len(failed)} of {len(results)} runs failed at the API level; do not quote these numbers.")
    print(f"\nwrote {out}")
    print((out / "summary.md").read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()
