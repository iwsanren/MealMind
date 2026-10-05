"""Data shapes shared by the tools, the verifier and the agent loop.

Meal mirrors the Java MealResponse; Recommendation is the final answer the agent must produce.
The Java RecommendationOutput record is the older, unstructured version of this schema and is NOT
kept in sync: this file is the source of truth for the agent's output.
"""

from enum import Enum

from pydantic import BaseModel, ConfigDict, Field, model_validator

# Must match backend enums/Allergen.java (the backend answers 400 with the valid list if they drift).
ALLERGEN_TOKENS = ["milk", "egg", "fish", "shellfish", "tree_nut", "peanut", "wheat", "soy", "sesame"]
AllergenToken = Enum("AllergenToken", {t.upper(): t for t in ALLERGEN_TOKENS}, type=str)  # type: ignore[misc]


class ClaimSource(str, Enum):
    FROM_CANDIDATE = "FROM_CANDIDATE"    # a fact about the recommended meal, checkable against its data
    FROM_PREFERENCE = "FROM_PREFERENCE"  # restates what the user asked for
    FROM_GUIDELINE = "FROM_GUIDELINE"    # supported by a passage from lookup_nutrition
    INFERRED = "INFERRED"                # the model's own inference; never checked


class ClaimField(str, Enum):
    """What kind of checkable fact a claim asserts. OTHER means nothing a program can check."""

    PRICE = "PRICE"                  # number = the price in USD
    WITHIN_BUDGET = "WITHIN_BUDGET"  # number = the user's budget in USD
    PROTEIN_G = "PROTEIN_G"          # number = grams of protein
    HIGH_PROTEIN = "HIGH_PROTEIN"    # asserts the meal counts as high protein; no value
    CALORIES = "CALORIES"            # number = kcal
    ALLERGEN_FREE = "ALLERGEN_FREE"  # allergen = the allergen the meal does not contain
    TAG = "TAG"                      # tag = one of the meal's tags, e.g. "High Protein"
    OTHER = "OTHER"


class Claim(BaseModel):
    """One factual statement. The value lives in a typed field (not one free-text string), so the model's
    constrained decoding can only produce a valid number or an allowed allergen there."""

    model_config = ConfigDict(extra="forbid")

    text: str = Field(description="The statement as shown to the user.")
    source: ClaimSource
    field: ClaimField
    number: float | None = Field(default=None, description="For PRICE, WITHIN_BUDGET, PROTEIN_G and CALORIES.")
    allergen: AllergenToken | None = Field(default=None, description="For ALLERGEN_FREE.")  # type: ignore[valid-type]
    tag: str | None = Field(default=None, description="For TAG: a tag the meal carries.")
    guideline_ref: str | None = Field(
        default=None, description="Exact [Source: ...] label from lookup_nutrition; required for FROM_GUIDELINE."
    )


class Recommendation(BaseModel):
    model_config = ConfigDict(extra="forbid")

    meal_id: int | None = Field(description="Id of a meal returned by search_meals, or null if none fits.")
    meal_name: str | None
    reason: str = Field(description="One or two sentences shown to the user.")
    claims: list[Claim] = Field(description="Every factual statement made in the reason.")
    no_match_reason: str | None = Field(default=None, description="Required when meal_id is null.")

    @model_validator(mode="after")
    def _consistent(self) -> "Recommendation":
        if not self.reason.strip():
            raise ValueError("reason must not be empty")
        if self.meal_id is None and not self.no_match_reason:
            raise ValueError("no_match_reason is required when meal_id is null")
        if self.meal_id is not None and not self.claims:
            raise ValueError("claims must not be empty when a meal is recommended")
        return self


class Meal(BaseModel):
    """One candidate as returned by the backend search (null facts mean UNKNOWN)."""

    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    id: int
    name: str
    price: float | None = None
    protein_g: float | None = Field(default=None, alias="proteinG")
    calories: int | None = None
    allergens: list[str] | None = None  # None = unknown, [] = known to contain none
    meal_time: list[str] = Field(default_factory=list, alias="mealTime")
    mood: list[str] = Field(default_factory=list)
    scene: list[str] = Field(default_factory=list)
    health_goal: list[str] = Field(default_factory=list, alias="healthGoal")
    cuisine: list[str] = Field(default_factory=list)
    taste: list[str] = Field(default_factory=list)
    convenience: list[str] = Field(default_factory=list)

    def all_tags(self) -> set[str]:
        tags = [*self.meal_time, *self.mood, *self.scene, *self.health_goal,
                *self.cuisine, *self.taste, *self.convenience]
        return {t.lower() for t in tags}


class Constraints(BaseModel):
    """Hard constraints the user stated. Unknown facts never satisfy a constraint that is set."""

    max_price: float | None = None
    exclude_allergens: list[str] = Field(default_factory=list)
