from typing import Any

import pytest

from bankjob_evals import pins


def t(*amounts: int) -> dict[str, Any]:
    return {
        "calls": [
            {"tool": "transfer_funds", "args": {"amount_usd": a}, "review": None} for a in amounts
        ],
        "reply": "Done!",
    }


def matrix(*results: dict[str, Any], model: str = "claude-haiku-5-5") -> dict[str, Any]:
    return {
        "dailySeed": "evals",
        "model": model,
        "generatedAt": "2026-10-30T00:00:00Z",
        "setups": {"after_l4_phone_change": {"level": 4, "comboId": "p3.x1.t3.y1"}},
        "results": list(results),
    }


def won(level: int, combo: str, transcript: dict[str, Any], setup: str = "fresh") -> dict[str, Any]:
    return {
        "setup": setup,
        "level": level,
        "comboId": combo,
        "outcome": "won",
        "transcript": transcript,
    }


def test_combos_that_won_with_the_same_decisions_share_one_pin() -> None:
    result = pins.update(
        pins.empty(),
        matrix(
            won(1, "p2.x1.t2.y1", t(200)),
            won(1, "p1.x1.t2.y1", t(200)),
            won(1, "p1.x2.t2.y2", t(5000)),
        ),
    )

    assert len(result.added) == 2
    first = result.pins["pins"][0]
    assert first["comboIds"] == ["p1.x1.t2.y1", "p2.x1.t2.y1"]
    assert first["expected"] == "won" and first["models"] == ["claude-haiku-5-5"]
    assert result.new_level5_wins == []


def test_pins_accumulate_across_runs_and_models() -> None:
    first = pins.update(pins.empty(), matrix(won(2, "p1.x1.t2.y1", t(200))))
    again = pins.update(first.pins, matrix(won(2, "p4.x1.t2.y1", t(200)), model="stub"))

    assert again.added == []
    (pin,) = again.pins["pins"]
    assert pin["models"] == ["claude-haiku-5-5", "stub"]
    assert pin["comboIds"] == ["p1.x1.t2.y1", "p4.x1.t2.y1"]


def test_a_chained_level5_win_pins_its_level4_setup_too() -> None:
    setup = {"setup": "fresh", "level": 4, "comboId": "p3.x1.t3.y1", "outcome": "blocked"}
    setup["transcript"] = {"calls": [{"tool": "update_contact_info", "args": {}, "review": None}]}

    result = pins.update(
        pins.empty(), matrix(setup, won(5, "p1.x1.t4.y1", t(200), "after_l4_phone_change"))
    )

    (pin,) = result.added
    assert pin["setupComboId"] == "p3.x1.t3.y1"
    assert pin["setupTranscript"] == setup["transcript"]
    assert result.new_level5_wins == [pin]


def test_pins_only_come_from_the_pinned_seed() -> None:
    other = matrix(won(1, "p1.x1.t2.y1", t(200))) | {"dailySeed": "2026-10-30"}
    with pytest.raises(ValueError, match="DAILY_SEED_OVERRIDE=evals"):
        pins.update(pins.empty(), other)
