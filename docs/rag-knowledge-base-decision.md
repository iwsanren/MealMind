# Why the nutrition knowledge base is a static file, not a vector store

Day 5/6 of the course (see `docs/day5.md` at the workspace root) introduces
RAG using a clinical example: a pharmacist reviewing a 78-year-old patient's
medication list against a 117-document, ~1.3M-token library of guidelines
and papers, retrieved via chunking + embedding + a vector database.

MealMind's knowledge-base need is the same *shape* of problem (ground the
LLM's claims in an authoritative source instead of letting it guess) but a
very different *scale*. This doc records the evaluation and the decision so
it doesn't have to be re-derived later, and so it's usable as interview
material ("why doesn't your RAG use a vector store?").

## What MealMind actually needs grounded

The LLM writes a one-sentence explanation for each meal recommendation. Two
kinds of content go into that explanation:

1. **Facts about the candidate meal and the user's own situation** — these
   already live in structured tables (`MealItem`, `SessionState.slots`) and
   are queried directly. This was never a RAG problem; it's normal
   persistence.
2. **General nutrition claims** (e.g. "this is a good source of protein",
   "this fits a balanced-diet goal") — these are the part that was
   previously either omitted or risked being guessed by the LLM from
   training data. This is the part that needed grounding.

Note explicitly what's **out of scope**: MealMind does not give clinical or
condition-specific advice. `RiskGuardService` already blocks medical claims
and special-population topics (pregnancy, diabetes, children, etc.) and
substitutes a fixed conservative message. So the knowledge base only needs
to cover general-adult, everyday nutrition guidance — not clinical
literature.

## Measured corpus size

Three sources were selected as authoritative, publicly available, and
non-clinical:

| Source | Scope used | Estimated size |
|---|---|---|
| Dietary Guidelines for Americans, 2025-2030 (USDA/HHS, realfood.gov) | Pages 1-5 of 10 (general-adult sections only; special-population pages 6-9 excluded) | ~1,400-1,600 tokens |
| FDA 21 CFR 101.54 (nutrient content claim definitions) | Full section | ~2,200-2,300 tokens |
| USDA MyPlate framework | Short summary | ~400-800 tokens (unverified — see note in the knowledge file) |
| **Total** | | **~4,000-5,000 tokens** |

Compare to the clinical example's ~1.3M tokens across 117 documents — roughly
two orders of magnitude smaller.

## Applying the four reasons for RAG

The course lists four reasons a system needs retrieval instead of stuffing
everything into the prompt: context-window limits, per-call cost, latency,
and relevance dilution. None of them hold at this scale:

- **Context window** — 4-5K tokens is a rounding error against a 1M-token
  window.
- **Cost per call** — negligible at this token count, even called on every
  recommendation.
- **Latency** — a few thousand extra input tokens does not meaningfully
  change response time for a "what should I eat" tool.
- **Relevance dilution** — this is the one that could have mattered, but is
  addressed by hand-curation: special-population content was excluded up
  front (see below), so everything left in the file is plausibly relevant
  to a general-adult meal recommendation.

## Decision

**No chunking, no embedding model, no vector database.** The curated
reference lives as a single static file,
`backend/src/main/resources/knowledge/nutrition_reference_v1.md`, and is
included in full inside the `meal_recommendation` prompt template
(`prompts/meal_recommendation/v3.txt`) rather than passed as a per-request
`{{variable}}`, because its content does not change per request — it changes
only when the underlying guidelines change, at which point it becomes a new
prompt version (same versioning story as any other prompt change).

Each fact in the reference file is tagged with its source
(`[Source: ...]`), and the prompt asks the LLM to tag any claim it draws
from that block as `[FROM_GUIDELINE]`, extending the existing
`[FROM_CANDIDATE]` / `[FROM_PREFERENCE]` / `[INFERRED]` source-attribution
scheme from `v2.txt` rather than inventing a separate mechanism.

This means what MealMind has is **augmented generation without a retrieval
step** — there's no "R" because there's nothing to select a subset of. It is
not the same thing as the clinical example's RAG pipeline, even though both
solve "don't let the LLM guess authoritative facts."

## When to revisit this

This decision is scoped to the current corpus. Reconsider chunking +
embedding + a vector store if any of the following becomes true:

- The knowledge base grows to the point where it no longer comfortably fits
  in every prompt (tens of documents / tens of thousands of tokens, not
  three short excerpts).
- Different requests genuinely need different subsets of the knowledge base
  (right now, every recommendation could plausibly use any part of it).
- The content needs to come from a source that changes frequently enough
  that re-cutting a new static file + prompt version each time becomes a
  bottleneck.
