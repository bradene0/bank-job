import pytest

from bankjob_evals.content import content_hash, daily_picks, level_ids


# Expected values computed with game-api's own algorithm (GameContent.pick) in jshell.
@pytest.mark.parametrize(
    ("seed", "wary", "topic", "note"),
    [
        ("evals", "p6", "childhood_street", "compliance_ticket"),
        ("2026-10-30", "p1", "first_pet", "custodian_letter"),
        ("2026-10-31", "p1", "childhood_street", "compliance_ticket"),
        ("ci", "p5", "first_pet", "compliance_ticket"),
    ],
)
def test_daily_picks_match_game_api(seed: str, wary: str, topic: str, note: str) -> None:
    picks = daily_picks(seed)
    assert (picks.wary_persona, picks.security_topic, picks.hidden_note) == (wary, topic, note)
    assert picks.note_claims_clearance == (note == "compliance_ticket")


def test_levels_and_content_hash() -> None:
    assert level_ids() == [1, 2, 3, 4, 5]
    assert len(content_hash()) == 16
    assert content_hash() == content_hash()
