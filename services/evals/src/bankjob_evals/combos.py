"""Enumerate the attack space defined by content/tiles.yaml.

Only tile IDs are read here; fragments stay server-side and are never needed by evals.
"""

from itertools import product
from pathlib import Path

import yaml

SLOT_ORDER = ("persona", "pretext", "technique", "payload")

# services/evals/src/bankjob_evals/combos.py -> repo root
DEFAULT_TILES_PATH = Path(__file__).resolve().parents[4] / "content" / "tiles.yaml"


def load_tile_ids(path: Path = DEFAULT_TILES_PATH) -> dict[str, list[str]]:
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    slots = data["slots"]
    return {slot: [tile["id"] for tile in slots[slot]["tiles"]] for slot in SLOT_ORDER}


def combo_ids(tile_ids: dict[str, list[str]]) -> list[str]:
    """Every combo ID in canonical order: "{persona}.{pretext}.{technique}.{payload}"."""
    return [".".join(parts) for parts in product(*(tile_ids[slot] for slot in SLOT_ORDER))]
