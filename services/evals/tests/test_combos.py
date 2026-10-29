import json
import re
from pathlib import Path

from bankjob_evals.combos import DEFAULT_TILES_PATH, SLOT_ORDER, combo_ids, load_tile_ids

SCHEMA_PATH = DEFAULT_TILES_PATH.parents[1] / "docs" / "contracts" / "attempt-request.schema.json"


def test_attack_space_is_864_unique_combos() -> None:
    ids = combo_ids(load_tile_ids())
    assert len(ids) == 864
    assert len(set(ids)) == 864


def test_tile_ids_match_the_attempt_request_contract() -> None:
    schema = json.loads(Path(SCHEMA_PATH).read_text(encoding="utf-8"))
    patterns = schema["properties"]["tiles"]["properties"]
    for slot, ids in load_tile_ids().items():
        pattern = re.compile(patterns[slot]["pattern"])
        assert all(pattern.fullmatch(tile_id) for tile_id in ids), slot


def test_combo_id_format() -> None:
    tile_ids = {slot: [f"{slot[0]}1"] for slot in SLOT_ORDER}
    assert combo_ids(tile_ids) == ["p1.p1.t1.p1"]
