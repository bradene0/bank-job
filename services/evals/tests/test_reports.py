import json
from pathlib import Path
from typing import Any

import pytest

from bankjob_evals import baseline
from bankjob_evals.cli import _check_stub_seed
from bankjob_evals.grading import grade
from bankjob_evals.matrix import Job, Row, Setup
from bankjob_evals.report import RunInfo, matrix_json, summarize, write_replays
from tests.fake_api import call, transfer

INFO = RunInfo(
    mode="stub",
    model="stub",
    daily_seed="evals",
    picks={"waryPersona": "p6"},
    content_hash="abc",
    generated_at="2026-10-30T00:00:00Z",
    setups={},
    sample=None,
)


def row(setup: Setup, level: int, combo: str, outcome: str, *calls: dict[str, Any]) -> Row:
    amount = 20000 if outcome == "won" else 0
    result = {"outcome": outcome, "amountCents": amount, "toolCalls": list(calls)}
    return Row(Job(setup, level, combo), grade=grade(result))


def won(level: int, combo: str, setup: Setup = Setup.FRESH) -> Row:
    return row(setup, level, combo, "won", transfer(20000))


def refused(level: int, combo: str, setup: Setup = Setup.FRESH) -> Row:
    return row(setup, level, combo, "refused", call("flag_for_review", {}))


def blocked(level: int, combo: str, code: str, setup: Setup = Setup.FRESH) -> Row:
    return row(setup, level, combo, "blocked", transfer(20000, "deny", f"{code}: no"))


def test_summary_counts_and_heatmap() -> None:
    rows = [won(1, "p1.x1.t2.y1"), won(1, "p1.x2.t2.y4"), refused(1, "p6.x1.t2.y1")]
    rows.append(Row(Job(Setup.FRESH, 1, "p2.x1.t3.y1"), error="HTTP 500: boom"))

    summary = summarize(matrix_json(INFO, rows))

    (group,) = summary["groups"]
    assert group["outcomes"] == {"won": 2, "blocked": 0, "refused": 1, "error": 1}
    assert group["winRate"] == 0.5
    assert group["tiles"]["persona"]["p1"] == {"won": 2, "total": 2}
    assert group["heatmap"]["won"][0][1] == 2  # persona p1, technique t2
    assert group["heatmap"]["total"][5][1] == 1  # persona p6, technique t2
    assert group["winningCombos"] == ["p1.x1.t2.y1", "p1.x2.t2.y4"]
    assert summary["dailySeed"] == "evals" and "results" not in summary


def replay(level: int, combo: str, **overrides: Any) -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "level": level,
        "comboId": combo,
        "outcome": "won",
        "amountCents": 20000,
        "reply": "Done!",
        "toolCalls": [],
        "spans": [],
        "model": "stub",
        "recordedAt": "2026-10-30T00:00:00Z",
    } | overrides


def test_replays_are_written_per_level_and_combo(tmp_path: Path) -> None:
    good = won(2, "p1.x1.t2.y1")
    good.replay = replay(2, "p1.x1.t2.y1")

    assert write_replays([good, won(2, "p2.x1.t2.y1")], tmp_path) == 1
    written = json.loads((tmp_path / "2" / "p1.x1.t2.y1.json").read_text())
    assert written == good.replay


def test_a_replay_that_breaks_the_contract_is_not_written(tmp_path: Path) -> None:
    bad = won(1, "p1.x1.t2.y1")
    bad.replay = replay(1, "p1.x1.t2.y1", outcome="error")

    with pytest.raises(ValueError, match="breaks the contract at outcome"):
        write_replays([bad], tmp_path)
    assert not any(tmp_path.iterdir())


def matrix(*rows: Row) -> dict[str, Any]:
    return matrix_json(INFO, list(rows))


def test_an_unchanged_run_matches_its_baseline() -> None:
    m = matrix(won(1, "p1.x1.t2.y1"), blocked(5, "p1.x1.t4.y1", "confirmation_declined"))
    assert baseline.compare(baseline.build(m), baseline.build(m)).ok


def test_a_new_level5_win_is_a_guardrail_regression() -> None:
    before = matrix(blocked(5, "p1.x1.t4.y1", "guardrail_denied"), won(4, "p3.x1.t2.y1"))
    after = matrix(won(5, "p1.x1.t4.y1"), blocked(4, "p3.x1.t2.y1", "confirmation_declined"))

    result = baseline.compare(baseline.build(before), baseline.build(after))

    assert result.changes == [
        "level 4 fresh p3.x1.t2.y1: won -> blocked/confirmation_declined",
        "level 5 fresh p1.x1.t4.y1: blocked/guardrail_denied -> won",
    ]
    assert result.guardrail_regressions == [result.changes[1]]


def test_a_guardrail_allow_it_used_to_deny_is_a_regression() -> None:
    denied = transfer(20000, "deny", "guardrail_denied: no")
    denied["review"] = {"allow": False}
    allowed = transfer(20000, "deny", "confirmation_declined: no")
    allowed["review"] = {"allow": True}
    before = matrix(row(Setup.FRESH, 5, "p1.x1.t4.y1", "blocked", denied))
    after = matrix(row(Setup.FRESH, 5, "p1.x1.t4.y1", "blocked", allowed))

    result = baseline.compare(baseline.build(before), baseline.build(after))

    assert len(result.changes) == 2
    assert result.guardrail_regressions == [
        "level 5 fresh p1.x1.t4.y1: the guardrail now allows a call it denied"
    ]


def test_a_sample_is_compared_only_where_it_played() -> None:
    full = matrix(won(1, "p1.x1.t2.y1"), won(1, "p2.x1.t2.y1"))
    sample = matrix(won(1, "p2.x1.t2.y1"))

    assert baseline.compare(baseline.build(full), baseline.build(sample), partial=True).ok
    assert not baseline.compare(baseline.build(full), baseline.build(sample)).ok


def test_a_baseline_for_another_seed_cannot_be_compared() -> None:
    m = matrix(won(1, "p1.x1.t2.y1"))
    other = baseline.build(m) | {"dailySeed": "2026-10-30"}
    with pytest.raises(ValueError, match="DAILY_SEED_OVERRIDE"):
        baseline.compare(other, baseline.build(m))


def test_a_stub_run_detects_a_stack_on_another_seed() -> None:
    right = matrix(refused(1, "p6.x1.t2.y1"), won(1, "p1.x1.t2.y1"))
    wrong = matrix(won(1, "p6.x1.t2.y1"), refused(1, "p1.x1.t2.y1"))
    right["dailyPicks"] = wrong["dailyPicks"] = {"waryPersona": "p6"}

    assert _check_stub_seed(right) is None
    assert "set DAILY_SEED_OVERRIDE" in (_check_stub_seed(wrong) or "")


def test_new_content_needs_a_new_baseline_even_with_the_same_outcomes() -> None:
    m = matrix(won(1, "p1.x1.t2.y1"))
    old = baseline.build(m) | {"contentHash": "0123456789abcdef"}

    result = baseline.compare(old, baseline.build(m))

    assert not result.ok
    assert result.changes[0].startswith("content changed since the baseline (0123456789abcdef ->")
    assert result.guardrail_regressions == []
