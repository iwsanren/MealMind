from app.config import DEFAULT_NUTRITION_FILE
from app.nutrition import NutritionReference


def real_reference() -> NutritionReference:
    return NutritionReference.from_file(DEFAULT_NUTRITION_FILE)


def test_parses_every_source_labelled_passage_of_the_real_file():
    labels = real_reference().source_labels()
    assert len(labels) >= 10
    assert 'DGA 2025-2030, "Prioritize Protein Foods at Every Meal"' in labels
    assert any(label.startswith("FDA 21 CFR 101.54(b)") for label in labels)


def test_lookup_protein_returns_the_protein_guidance_first():
    top = real_reference().lookup("protein")[0]
    assert "Protein Foods" in top.source
    assert "1.2-1.6" in top.text


def test_lookup_uses_synonyms_and_ignores_stopwords():
    passages = real_reference().lookup("how much salt is too much")
    assert any("Sodium" in p.source for p in passages)


def test_fda_and_myplate_passages_carry_a_caveat_but_dga_does_not():
    ref = real_reference()
    fda = [p for p in ref.lookup("high excellent source") if p.source.startswith("FDA")]
    myplate = [p for p in ref.lookup("myplate plate groups") if "MyPlate" in p.source]
    assert fda and all("Paraphrased" in p.caveat for p in fda)
    assert myplate and all("Unverified" in p.caveat for p in myplate)
    assert all(p.caveat is None for p in ref.lookup("protein") if p.source.startswith("DGA"))


def test_unmatched_topic_returns_nothing_rather_than_something_random():
    assert real_reference().lookup("quantum chromodynamics") == []
    assert real_reference().lookup("   ") == []


def test_prose_outside_labelled_blocks_is_never_returned():
    ref = NutritionReference.from_text("## Heading\nIntro prose about protein.\n\n[Source: A, \"B\"]\nReal protein passage.\n\n---\nTrailing protein note.")
    assert [p.text for p in ref.lookup("protein")] == ["Real protein passage."]
