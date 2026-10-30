"""What the harness needs from content/: level ids, the day's rotation and a content hash.

Prompt fragments are never read here. The harness plays tile ids against the API like the
browser does, and grades from the API's answer.
"""

import hashlib
from dataclasses import dataclass
from pathlib import Path

import yaml

# services/evals/src/bankjob_evals/content.py -> repo root
REPO_ROOT = Path(__file__).resolve().parents[4]
CONTENT_DIR = REPO_ROOT / "content"
CONTRACTS_DIR = REPO_ROOT / "docs" / "contracts"


@dataclass(frozen=True)
class DailyPicks:
    """What one daily seed selects from content/daily.yaml (game-api's GameContent.picks)."""

    seed: str
    wary_persona: str
    security_topic: str
    hidden_note: str
    note_claims_clearance: bool


def _pick(seed: str, list_name: str, size: int) -> int:
    # Same as GameContent.pick in game-api: the first 4 bytes of SHA-256("{seed}|{list}") as a
    # signed big-endian int, floor-modded by the list size. Python's % is already a floor mod.
    digest = hashlib.sha256(f"{seed}|{list_name}".encode()).digest()
    return int.from_bytes(digest[:4], "big", signed=True) % size


def daily_picks(seed: str, path: Path = CONTENT_DIR / "daily.yaml") -> DailyPicks:
    daily = yaml.safe_load(path.read_text(encoding="utf-8"))
    personas = daily["wary_of_persona"]
    topics = daily["security_question_topics"]
    notes = daily["hidden_notes"]
    note = notes[_pick(seed, "hidden_note", len(notes))]
    return DailyPicks(
        seed=seed,
        wary_persona=personas[_pick(seed, "wary_of_persona", len(personas))],
        security_topic=topics[_pick(seed, "security_question_topic", len(topics))]["id"],
        hidden_note=note["id"],
        note_claims_clearance=bool(note["claims_clearance"]),
    )


def level_ids(path: Path = CONTENT_DIR / "levels.yaml") -> list[int]:
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    return [int(level["id"]) for level in data["levels"]]


def content_hash(root: Path = CONTENT_DIR) -> str:
    """Changes whenever any file under content/ changes (tiles, levels, rotation, prompts)."""
    sha = hashlib.sha256()
    for file in sorted(p for p in root.rglob("*") if p.is_file()):
        sha.update(file.relative_to(root).as_posix().encode())
        sha.update(b"\0")
        sha.update(file.read_bytes())
    return sha.hexdigest()[:16]
