"""Recompute the headline numbers straight from a run's raw runs.jsonl (no API calls, no cost, exact).

    python evaluation/recompute_headline.py                                   # the valid full run
    python evaluation/recompute_headline.py evaluation/results/<stamp>        # another run directory

Each line of runs.jsonl is one run with its own score flags, so every percentage in summary.md is a count over these lines.
The data is synthetic (see summary.md); these numbers say nothing about real menus.
"""

import json
import sys
from pathlib import Path

DEFAULT = Path(__file__).resolve().parent / "results" / "20261005-171415"


def main() -> None:
    directory = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT
    rows = [json.loads(line) for line in (directory / "runs.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
    print(f"{directory}: {len(rows)} runs")
    for arm in sorted({r["arm"] for r in rows}):
        runs = [r for r in rows if r["arm"] == arm]
        n = len(runs)
        unsafe = sum(1 for r in runs if r["score"]["unsafe"])
        correct = sum(1 for r in runs if r["score"]["correct_outcome"])
        false_no_match = sum(1 for r in runs if r["score"]["false_no_match"])
        cost = sum(r["cost_usd"] for r in runs) / n
        print(f"arm {arm}: unsafe {unsafe}/{n} = {unsafe / n:.1%} | correct outcome {correct}/{n} = {correct / n:.1%} | "
              f"'nothing fits' when something did {false_no_match}/{n} | mean cost per run ${cost:.5f}")


if __name__ == "__main__":
    main()
