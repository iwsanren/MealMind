import asyncio

import pytest

from app.fake_backend import FakeBackend

_CONFIG_ENV_VARS = [
    "OPENAI_API_KEY", "OPENAI_MODEL", "LLM_API_URL", "AGENT_MODEL", "AGENT_TEMPERATURE", "LLM_TIMEOUT_S",
    "LLM_MAX_RETRIES", "AGENT_PROMPT_VERSION", "AGENT_MAX_ROUNDS", "AGENT_MAX_FORMAT_RETRIES", "AGENT_MAX_VERIFY_RETRIES", "INTERNAL_API_BASE_URL", "BACKEND_TIMEOUT_S", "NUTRITION_REFERENCE_FILE",
    "AGENT_DEADLINE_S", "AGENT_REQUEST_CAP_USD", "AGENT_ENGINE", "AGENT_CHECKPOINT_DB",
]


@pytest.fixture(autouse=True)
def isolated_env(monkeypatch):
    """Settings reads real environment variables; a developer shell must not change test outcomes."""
    for name in _CONFIG_ENV_VARS:
        monkeypatch.delenv(name, raising=False)


def run(coro):
    return asyncio.run(coro)


SLOT_OPTIONS = {
    "mealTime": ["Breakfast", "Lunch", "Dinner"],
    "mood": ["Tired", "Happy", "Want to Treat Myself"],
    "scene": ["Work", "Home"],
    "healthGoal": ["High Protein", "Light", "Fat Loss"],
    "cuisine": ["Western", "Healthy Food"],
    "taste": ["Savory", "Sweet"],
    "convenience": ["Quick", "Easy Takeout"],
}


def meal(id, name, price=10.0, protein=20.0, calories=500, allergens=(), source="PUBLIC", owner=None, **tags):
    """A meal in the backend's JSON shape. Pass allergens=None for 'unknown'."""
    base = {"id": id, "sourceType": source, "ownerUserId": owner, "name": name,
            "mealTime": ["Dinner"], "mood": [], "scene": [], "healthGoal": [], "cuisine": [], "taste": [], "convenience": [],
            "price": price, "proteinG": protein, "calories": calories,
            "allergens": None if allergens is None else list(allergens)}
    base.update(tags)
    return base


@pytest.fixture
def catalog():
    return [
        meal(1, "Chicken Bowl", price=12.5, protein=38.0, allergens=["milk"], healthGoal=["High Protein"]),
        meal(2, "Shrimp Pasta", price=14.0, protein=24.0, allergens=["shellfish", "wheat"], healthGoal=["High Protein"]),
        meal(3, "Oats", price=6.5, protein=8.0, allergens=[], healthGoal=["Light"]),
        meal(4, "Mystery Stew", price=None, protein=None, calories=None, allergens=None),
        meal(5, "Luxury Steak", price=40.0, protein=50.0, allergens=[], healthGoal=["High Protein"]),
    ]


@pytest.fixture
def backend(catalog):
    return FakeBackend(catalog, slot_options=SLOT_OPTIONS)
