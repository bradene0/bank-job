"""Pinned wins: every winning decision any run has found, kept as a regression test.

A pin is what the model decided in a winning attempt (its tool calls, their arguments and any
guardrail verdicts), not just the combo: the policy engine only ever sees the calls, so two combos
that won with the same calls are one test. game-api's PinnedWinsIT replays every pin through the
current policy engine and ledger (the response cache path, ADR-0012) and checks its expected
outcome. So a policy change cannot quietly close an intended gap or reopen a closed one.

Pins only accumulate. When a fix closes a hole on purpose, set that pin's `expected` to the new
outcome and give the reason in `note`; it then guards the fix.
"""

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

PINS_PATH = Path(__file__).resolve().parents[2] / "pins.json"
# Pins replay against branches provisioned for this seed (security answers, the hidden note).
PINNED_SEED = "evals"
GUARDED_LEVEL = 5


def pin_id(level: int, setup: str, transcript: dict[str, Any], setup_transcript: Any) -> str:
    decisions = {
        "level": level,
        "setup": setup,
        "calls": transcript["calls"],
        "setupCalls": setup_transcript["calls"] if setup_transcript else None,
    }
    canonical = json.dumps(decisions, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode()).hexdigest()[:16]


@dataclass(frozen=True)
class Update:
    pins: dict[str, Any]
    added: list[dict[str, Any]]

    @property
    def new_level5_wins(self) -> list[dict[str, Any]]:
        return [p for p in self.added if p["level"] == GUARDED_LEVEL]


def empty() -> dict[str, Any]:
    return {"schemaVersion": 1, "dailySeed": PINNED_SEED, "pins": []}


def update(pins: dict[str, Any], matrix: dict[str, Any]) -> Update:
    if matrix["dailySeed"] != pins["dailySeed"]:
        raise ValueError(
            f"pins replay on daily seed {pins['dailySeed']!r}; this run used"
            f" {matrix['dailySeed']!r} (start the stack with DAILY_SEED_OVERRIDE={PINNED_SEED})"
        )
    fresh_l4 = {
        r["comboId"]: r.get("transcript")
        for r in matrix["results"]
        if r["setup"] == "fresh" and r["level"] == 4
    }
    by_id = {p["id"]: p for p in pins["pins"]}
    added = []
    for row in matrix["results"]:
        if row["outcome"] != "won":
            continue
        setup_combo = None
        setup_transcript = None
        if row["setup"] != "fresh":
            setup_combo = matrix["setups"][row["setup"]]["comboId"]
            setup_transcript = fresh_l4.get(setup_combo)
            if setup_transcript is None:
                raise ValueError(f"no transcript for setup {setup_combo}; rerun the matrix")
        pid = pin_id(row["level"], row["setup"], row["transcript"], setup_transcript)
        pin = by_id.get(pid)
        if pin is None:
            pin = {
                "id": pid,
                "level": row["level"],
                "setup": row["setup"],
                "expected": "won",
                "firstWonAt": matrix["generatedAt"],
                "models": [],
                "comboIds": [],
                "setupComboId": setup_combo,
                "transcript": row["transcript"],
                "setupTranscript": setup_transcript,
            }
            by_id[pid] = pin
            added.append(pin)
        if matrix["model"] not in pin["models"]:
            pin["models"] = sorted([*pin["models"], matrix["model"]])
        if row["comboId"] not in pin["comboIds"]:
            pin["comboIds"] = sorted([*pin["comboIds"], row["comboId"]])
    ordered = sorted(by_id.values(), key=lambda p: (p["level"], p["setup"], p["comboIds"][0]))
    return Update(pins | {"pins": ordered}, added)


def load(path: Path = PINS_PATH) -> dict[str, Any]:
    if not path.exists():
        return empty()
    pins: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    return pins
