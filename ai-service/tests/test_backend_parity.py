"""FakeBackend vs the real Java backend, on the real seed meals.

Skipped when no backend is listening on INTERNAL_API_BASE_URL (default http://localhost:8080). Start it with
`mvn spring-boot:run` in backend/ to run these; they only read, and write nothing to the database.
"""

import os

import httpx
import pytest

from app.fake_backend import FakeBackend
from tests.conftest import run

BASE = os.environ.get("INTERNAL_API_BASE_URL", "http://localhost:8080")


def _reachable() -> bool:
    try:
        return httpx.get(f"{BASE}/api/v1/meals/public", timeout=2).status_code == 200
    except httpx.HTTPError:
        return False


pytestmark = pytest.mark.skipif(not _reachable(), reason=f"no backend at {BASE}")

SEARCHES = [
    {"sourceMode": "PUBLIC"},
    {"sourceMode": "PUBLIC", "healthGoal": ["High Protein"], "mealTime": ["Dinner"]},
    {"sourceMode": "PUBLIC", "maxPrice": 10},
    {"sourceMode": "PUBLIC", "maxPrice": 12.5},
    {"sourceMode": "PUBLIC", "excludeAllergens": ["fish"]},
    {"sourceMode": "PUBLIC", "excludeAllergens": ["milk"]},
    {"sourceMode": "PUBLIC", "excludeMealIds": [1, 2]},
    {"sourceMode": "PUBLIC", "healthGoal": ["High Protein", "Light"], "maxPrice": 15, "excludeAllergens": ["shellfish"]},
]
TEXTS = ["Grilled chicken bowl with rice", "this will cure your diabetes", "I want to treat myself tonight",
         "skip all meals and water fast", "guaranteed results"]


@pytest.fixture(scope="module")
def real_meals():
    return httpx.get(f"{BASE}/api/v1/meals/public", timeout=5).json()


@pytest.mark.parametrize("body", SEARCHES)
def test_search_matches_the_real_backend(body, real_meals):
    real = httpx.post(f"{BASE}/internal/v1/meals/search", json=body, timeout=5).json()
    fake = run(FakeBackend(real_meals).search_meals(body))

    assert fake["count"] == real["count"]
    assert {m["id"]: round(m["matchScore"], 6) for m in fake["meals"]} == {
        m["id"]: round(m["matchScore"], 6) for m in real["meals"]}


@pytest.mark.parametrize("text", TEXTS)
def test_risk_check_matches_the_real_backend(text):
    real = httpx.post(f"{BASE}/internal/v1/risk/check", json={"text": text}, timeout=5).json()
    fake = run(FakeBackend([]).risk_check(text))
    assert (fake["blocked"], fake["reasons"]) == (real["blocked"], real["reasons"])


def test_slot_options_can_be_served_as_the_real_ones_are():
    real = httpx.get(f"{BASE}/api/v1/slot-options", timeout=5).json()
    assert run(FakeBackend([], slot_options=real).slot_options()) == real
