"""The run's outputs: matrix.json (one row per played combo), summary.json (counts per level and
setup, shaped for heatmaps) and replay files (docs/contracts/replay.schema.json)."""

import json
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator

from bankjob_evals.api import SLOTS
from bankjob_evals.combos import load_tile_ids
from bankjob_evals.content import CONTRACTS_DIR
from bankjob_evals.matrix import Row

REPLAY_SCHEMA = CONTRACTS_DIR / "replay.schema.json"
# Winning combos are listed in the summary when there are at most this many in a group.
LIST_WINS_UP_TO = 100


@dataclass(frozen=True)
class RunInfo:
    mode: str  # "stub", or "live" (Penny is a real model)
    model: str
    daily_seed: str
    picks: dict[str, Any]
    content_hash: str
    generated_at: str
    setups: dict[str, Any]  # chain setup -> the Level 4 combo that set it up (or null)
    sample: dict[str, Any] | None
    usage: dict[str, Any] | None = None  # batch mode: requests and tokens spent

    def as_json(self) -> dict[str, Any]:
        info = {
            "schemaVersion": 1,
            "generatedAt": self.generated_at,
            "mode": self.mode,
            "model": self.model,
            "dailySeed": self.daily_seed,
            "dailyPicks": self.picks,
            "contentHash": self.content_hash,
            "setups": self.setups,
            "sample": self.sample,
        }
        if self.usage is not None:
            info["usage"] = self.usage
        return info


def row_json(row: Row) -> dict[str, Any]:
    out: dict[str, Any] = {
        "setup": row.job.setup.value,
        "level": row.job.level,
        "comboId": row.job.combo_id,
    }
    if row.grade is None:
        out["outcome"] = "error"
        out["error"] = row.error
        return out
    g = row.grade
    out |= {
        "outcome": g.outcome,
        "amountCents": g.amount_cents,
        "reached": g.reached,
        "stoppedBy": g.stopped_by,
        "denials": list(g.denials),
        "guardrail": list(g.guardrail),
        "flagged": g.flagged,
        "vaultPhoneChanged": g.vault_phone_changed,
        "cached": row.cached,
        "attemptId": row.attempt_id,
        "traceId": row.trace_id,
    }
    if row.transcript is not None:
        out["transcript"] = row.transcript
    return out


def matrix_json(info: RunInfo, rows: list[Row]) -> dict[str, Any]:
    return info.as_json() | {"results": [row_json(r) for r in rows]}


def _cell() -> dict[str, int]:
    return {"won": 0, "total": 0}


def summarize(matrix: dict[str, Any]) -> dict[str, Any]:
    """Counts per (setup, level), win rates per tile, and a persona x technique heatmap (each
    cell sums the 6 pretexts x 4 payloads behind it)."""
    tile_ids = load_tile_ids()
    groups: dict[tuple[str, int], list[dict[str, Any]]] = defaultdict(list)
    for r in matrix["results"]:
        groups[(r["setup"], r["level"])].append(r)

    out = []
    for (setup, level), rows in sorted(groups.items(), key=lambda kv: (kv[0][1], kv[0][0])):
        outcomes = Counter(r["outcome"] for r in rows)
        tiles: dict[str, dict[str, dict[str, int]]] = {
            slot: {tile: _cell() for tile in tile_ids[slot]} for slot in SLOTS
        }
        personas, techniques = tile_ids["persona"], tile_ids["technique"]
        won_grid = [[0] * len(techniques) for _ in personas]
        total_grid = [[0] * len(techniques) for _ in personas]
        for r in rows:
            won = r["outcome"] == "won"
            parts = r["comboId"].split(".")
            for slot, tile in zip(SLOTS, parts, strict=True):
                tiles[slot][tile]["total"] += 1
                tiles[slot][tile]["won"] += won
            i, j = personas.index(parts[0]), techniques.index(parts[2])
            total_grid[i][j] += 1
            won_grid[i][j] += won
        wins = sorted(r["comboId"] for r in rows if r["outcome"] == "won")
        group: dict[str, Any] = {
            "level": level,
            "setup": setup,
            "total": len(rows),
            "outcomes": {k: outcomes.get(k, 0) for k in ("won", "blocked", "refused", "error")},
            "winRate": round(outcomes.get("won", 0) / len(rows), 4),
            "amountWonCents": sum(r.get("amountCents", 0) for r in rows),
            "stoppedBy": dict(Counter(r["stoppedBy"] for r in rows if r.get("stoppedBy"))),
            "reached": dict(Counter(r.get("reached") or "nothing" for r in rows)),
            "guardrailAllows": sum(
                1 for r in rows for v in r.get("guardrail", []) if v.endswith(":allow")
            ),
            "tiles": tiles,
            "heatmap": {
                "rows": "persona",
                "columns": "technique",
                "rowIds": personas,
                "columnIds": techniques,
                "won": won_grid,
                "total": total_grid,
            },
        }
        if len(wins) <= LIST_WINS_UP_TO:
            group["winningCombos"] = wins
        out.append(group)

    info = {k: v for k, v in matrix.items() if k != "results"}
    return info | {"groups": out}


def replay_validator() -> Draft202012Validator:
    schema = json.loads(REPLAY_SCHEMA.read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema)


def write_replays(rows: list[Row], directory: Path) -> int:
    """One file per fresh (level, combo) at {directory}/{level}/{comboId}.json, each validated
    against the replay contract before it is written. Returns the number written."""
    validator = replay_validator()
    written = 0
    for row in rows:
        if row.replay is None:
            continue
        errors = sorted(validator.iter_errors(row.replay), key=lambda e: list(e.path))
        if errors:
            where = "/".join(str(p) for p in errors[0].path)
            raise ValueError(
                f"replay for level {row.job.level} {row.job.combo_id} breaks the contract at"
                f" {where or '(root)'}: {errors[0].message}"
            )
        path = directory / str(row.job.level) / f"{row.job.combo_id}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(row.replay, separators=(",", ":")), encoding="utf-8")
        written += 1
    return written


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=1) + "\n", encoding="utf-8")
