# Evaluation results (20261005-171008)

Model `gpt-4o-mini` (temperature 0.0); agent prompt `v5`, baseline prompt `v1`. 4 cases entered the comparison, 1 repeats each. Real spend: $0.0162.

> **All data is synthetic.** The 40-meal catalog and the 40 cases were written to contain edge cases (budget and protein boundaries, unknown facts, mislabeled tags, multi-allergen dishes); they say how each arm handles those cases, not how accurate any arm is on real menus. Repeats of one case are not independent, so intervals are a guide.

## Arms

- **A** - baseline (one call, whole menu)
- **B** - baseline + verification (oracle constraints)
- **C** - agent (tools + verification)

## Headline (per run; lower is better for the first row, higher for the others)

| metric | A | B | C |
|---|---|---|---|
| unsafe answer (any unsupported claim, hard-constraint violation or invented dish) | 25.0% (1/4) | 0.0% (0/4) | 0.0% (0/4) |
|   of which hard-constraint violation (budget / allergen / protein need) | 25.0% (1/4) | 0.0% (0/4) | 0.0% (0/4) |
|   of which unsupported claim | 0.0% (0/4) | 0.0% (0/4) | 0.0% (0/4) |
|   of which invented dish | 0.0% (0/4) | 0.0% (0/4) | 0.0% (0/4) |
| said 'nothing fits' when something did | 0.0% (0/4) | 25.0% (1/4) | 0.0% (0/4) |
| **correct outcome** (safe, and the right kind of answer) | 75.0% (3/4) | 75.0% (3/4) | 100.0% (4/4) |
| no answer shown (parse failure, withheld, error) | 0.0% (0/4) | 0.0% (0/4) | 0.0% (0/4) |
| recommended a disliked meal | 0.0% (0/4) | 0.0% (0/4) | 0.0% (0/4) |
| off the requested meal time (of runs that recommended a meal) | 0.0% (0/3) | 0.0% (0/2) | 0.0% (0/3) |

95% Wilson intervals (unsafe / correct):

- A: unsafe 4.6-69.9%, correct 30.1-95.4%
- B: unsafe 0.0-49.0%, correct 30.1-95.4%
- C: unsafe 0.0-49.0%, correct 51.0-100.0%

## Cost and speed

| | A | B | C |
|---|---|---|---|
| model calls per run | 1.00 | 1.25 | 3.75 |
| input / output tokens per run | 4792 / 224 | 6099 / 278 | 12640 / 379 |
| USD per run | $0.00085 | $0.00108 | $0.00212 |
| latency p50 / p95 | 3.9s / 4.2s | 3.7s / 5.2s | 9.5s / 12.8s |

Statuses: A: {'SUCCESS': 4}; B: {'SUCCESS': 4}; C: {'SUCCESS': 4}

## Paired by case (mean over repeats; positive = the left arm is worse on 'unsafe' / better on 'correct')

- A-C:unsafe: mean difference +0.250 (bootstrap 95% CI +0.000 to +0.750) over 4 cases; left higher in 1, right higher in 0, equal in 3
- A-C:correct_outcome: mean difference -0.250 (bootstrap 95% CI -0.750 to +0.000) over 4 cases; left higher in 0, right higher in 1, equal in 3
- A-B:unsafe: mean difference +0.250 (bootstrap 95% CI +0.000 to +0.750) over 4 cases; left higher in 1, right higher in 0, equal in 3
- A-B:correct_outcome: mean difference +0.000 (bootstrap 95% CI +0.000 to +0.000) over 4 cases; left higher in 0, right higher in 0, equal in 4
- B-C:unsafe: mean difference +0.000 (bootstrap 95% CI +0.000 to +0.000) over 4 cases; left higher in 0, right higher in 0, equal in 4
- B-C:correct_outcome: mean difference -0.250 (bootstrap 95% CI -0.750 to +0.000) over 4 cases; left higher in 0, right higher in 1, equal in 3

## By category (unsafe runs / correct runs, of runs in that category)

| category | A unsafe | A correct | B unsafe | B correct | C unsafe | C correct |
|---|---|---|---|---|---|---|
| budget | 0/1 | 1/1 | 0/1 | 1/1 | 0/1 | 1/1 |
| dislike | 0/1 | 1/1 | 0/1 | 1/1 | 0/1 | 1/1 |
| high_protein | 1/1 | 0/1 | 0/1 | 0/1 | 0/1 | 1/1 |
| unknown_trap | 0/1 | 1/1 | 0/1 | 1/1 | 0/1 | 1/1 |

## RiskGuard gate (runs before any arm; the same for every arm)

- medical messages stopped: 3/3
- benign messages containing the word 'treat' stopped (false positives): 3/3
- cases kept out of the comparison because the gate stopped them: m1, m2, m3, t1, t2, t3
