from pathlib import Path

PROMPTS_DIR = Path(__file__).resolve().parents[1] / "prompts"


def load_prompt(kind: str, version: str) -> str:
    """Prompt files are versioned (prompts/<kind>/<version>.txt) and the version is recorded in every trace/result."""
    path = PROMPTS_DIR / kind / f"{version}.txt"
    try:
        return path.read_text(encoding="utf-8")
    except OSError as e:
        raise FileNotFoundError(f"prompt '{kind}/{version}' not found at {path}") from e


def load_agent_prompt(version: str) -> str:
    return load_prompt("agent_system", version)


def load_baseline_prompt(version: str) -> str:
    return load_prompt("baseline", version)
