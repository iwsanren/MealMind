"""Read-only lookup over the curated nutrition reference (backend/.../knowledge/nutrition_reference_v1.md).

The file is small (a few thousand tokens), so this is plain keyword matching, not retrieval infrastructure.
See notes/rag-knowledge-base-decision.md for why there is no vector store.
"""

import re
from dataclasses import dataclass
from pathlib import Path

_SOURCE_LINE = re.compile(r"^\[Source: (?P<label>.+)\]\s*$")

# Words a model is likely to use mapped to words that actually occur in the reference passages.
_SYNONYMS = {
    "salt": "sodium", "sugar": "sugars", "sweet": "sugars", "fat": "fats", "fats": "fats",
    "veggies": "vegetables", "veggie": "vegetables", "fruit": "fruits", "grain": "grains", "carbs": "refined",
    "carb": "refined", "calorie": "calorie", "calories": "calorie", "kcal": "calorie", "meat": "protein",
}
_STOPWORDS = {"a", "an", "the", "of", "for", "and", "or", "in", "to", "is", "are", "what", "how", "much"}

# Passages that are paraphrases or unverified; surfaced so the model does not over-trust them.
_CAVEATS = {
    "FDA 21 CFR": "Paraphrased summary, not verbatim regulatory text.",
    "MyPlate": "Unverified paraphrase of the public MyPlate framework.",
}


@dataclass(frozen=True)
class Passage:
    source: str   # the exact label to cite as guideline_ref, without the surrounding "[Source: ]"
    text: str
    caveat: str | None = None


class NutritionReference:
    def __init__(self, passages: list[Passage]):
        self._passages = passages

    @classmethod
    def from_file(cls, path: Path) -> "NutritionReference":
        return cls.from_text(path.read_text(encoding="utf-8"))

    @classmethod
    def from_text(cls, text: str) -> "NutritionReference":
        passages: list[Passage] = []
        label: str | None = None
        body: list[str] = []

        def flush() -> None:
            if label is not None and body:
                joined = " ".join(line.strip() for line in body if line.strip())
                caveat = next((c for key, c in _CAVEATS.items() if key in label), None)
                passages.append(Passage(source=label, text=joined, caveat=caveat))

        for line in text.splitlines():
            match = _SOURCE_LINE.match(line.strip())
            if match:
                flush()
                label, body = match.group("label"), []
            elif line.strip() in {"---"} or line.startswith("## "):
                flush()
                label, body = None, []
            elif label is not None:
                body.append(line)
        flush()
        return cls(passages)

    def source_labels(self) -> set[str]:
        return {p.source for p in self._passages}

    def lookup(self, topic: str, max_results: int = 3) -> list[Passage]:
        words = [_SYNONYMS.get(w, w) for w in re.findall(r"[a-z0-9]+", topic.lower()) if w not in _STOPWORDS]
        if not words:
            return []
        scored = []
        for passage in self._passages:
            haystack = f"{passage.source} {passage.text}".lower()
            score = sum(1 for w in words if w in haystack)
            if score:
                scored.append((score, passage))
        scored.sort(key=lambda pair: -pair[0])  # stable: file order breaks ties
        return [p for _, p in scored[:max_results]]
