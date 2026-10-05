"""Token prices and a hard spending cap for real API calls."""

# USD per 1M tokens (input, output), standard tier, copied from the official OpenAI pricing page on 2026-10-05
# (https://developers.openai.com/api/docs/pricing). An unknown model raises instead of guessing a price.
PRICES_PER_MILLION: dict[str, tuple[float, float]] = {
    "gpt-4o-mini": (0.15, 0.60),
    "gpt-4.1-mini": (0.40, 1.60),
    "gpt-4.1-nano": (0.10, 0.40),
    "gpt-5-mini": (0.25, 2.00),
    "gpt-5.4-mini": (0.75, 4.50),
    "gpt-5.4-nano": (0.20, 1.25),
}


def cost_usd(model: str, prompt_tokens: int, completion_tokens: int) -> float:
    if model not in PRICES_PER_MILLION:
        raise KeyError(f"no known price for model '{model}'; add it to PRICES_PER_MILLION from the official pricing page")
    in_price, out_price = PRICES_PER_MILLION[model]
    return (prompt_tokens * in_price + completion_tokens * out_price) / 1_000_000


class BudgetExceeded(Exception):
    pass


class SpendTracker:
    """Accumulates cost across calls and refuses further calls once the cap would be crossed."""

    def __init__(self, cap_usd: float):
        self.cap_usd = cap_usd
        self.spent_usd = 0.0
        self.calls = 0

    def check(self) -> None:
        if self.spent_usd >= self.cap_usd:
            raise BudgetExceeded(f"spend cap ${self.cap_usd:.2f} reached (${self.spent_usd:.4f} spent)")

    def add(self, model: str, prompt_tokens: int, completion_tokens: int) -> float:
        cost = cost_usd(model, prompt_tokens, completion_tokens)
        self.spent_usd += cost
        self.calls += 1
        return cost
