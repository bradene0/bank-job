"""Property tests: whatever game-api or a caller sends, parsing either succeeds with valid
data or fails with a validation error. Nothing else escapes, and nothing but a token and ids
is ever accepted on the agent's HTTP API."""

import contextlib
import re
from typing import Any

from hypothesis import given
from hypothesis import strategies as st
from pydantic import ValidationError

from bankjob_agent.app import ReviewRequest, RunRequest
from bankjob_agent.briefs import AttemptBrief, ReviewBrief
from tests.fakes import make_brief

TOKEN_CHARS = re.compile(r"^[A-Za-z0-9_\-.]{20,4096}$")

json_values = st.recursive(
    st.none() | st.booleans() | st.integers() | st.floats(allow_nan=False) | st.text(max_size=20),
    lambda children: (
        st.lists(children, max_size=4) | st.dictionaries(st.text(max_size=10), children, max_size=4)
    ),
    max_leaves=20,
)
VALID_BRIEF = make_brief("p3.x1.t4.y4").model_dump(by_alias=True)


@given(st.dictionaries(st.text(max_size=12), json_values, max_size=8))
def test_arbitrary_objects_never_crash_brief_parsing(data: dict[str, Any]) -> None:
    for model in (AttemptBrief, ReviewBrief):
        with contextlib.suppress(ValidationError):
            model.model_validate(data)


@given(st.text(max_size=200))
def test_arbitrary_text_never_crashes_brief_parsing(raw: str) -> None:
    with contextlib.suppress(ValidationError):
        AttemptBrief.model_validate_json(raw)


@given(st.sampled_from(sorted(VALID_BRIEF)), json_values)
def test_corrupting_one_field_is_caught_or_harmless(field: str, value: Any) -> None:
    data = VALID_BRIEF | {field: value}
    try:
        brief = AttemptBrief.model_validate(data)
    except ValidationError:
        return
    # If it parsed, the brief still satisfies the invariants the teller relies on.
    assert 1 <= brief.level <= 5
    assert brief.max_tool_calls >= 1
    tiles = brief.tiles
    assert brief.combo_id == f"{tiles.persona}.{tiles.pretext}.{tiles.technique}.{tiles.payload}"


@given(st.dictionaries(st.text(max_size=12), json_values, max_size=3))
def test_unknown_brief_fields_are_ignored(extra: dict[str, Any]) -> None:
    known = set(VALID_BRIEF)
    extra = {k: v for k, v in extra.items() if k not in known and k not in _snake_names()}
    assert AttemptBrief.model_validate(VALID_BRIEF | extra) == AttemptBrief.model_validate(
        VALID_BRIEF
    )


def _snake_names() -> set[str]:
    return set(AttemptBrief.model_fields)


@given(st.text(max_size=4200))
def test_only_jwt_shaped_tokens_are_accepted(token: str) -> None:
    try:
        RunRequest.model_validate({"attemptToken": token})
    except ValidationError:
        assert not TOKEN_CHARS.match(token)
    else:
        assert TOKEN_CHARS.match(token)


@given(
    st.dictionaries(
        st.text(min_size=1, max_size=12).filter(lambda k: k not in ("attemptToken", "reviewId")),
        json_values,
        min_size=1,
        max_size=3,
    )
)
def test_requests_reject_any_extra_field(extra: dict[str, Any]) -> None:
    token = "a" * 40
    review_id = "0190f0e0-0000-7000-8000-0000000000aa"
    for model, base in (
        (RunRequest, {"attemptToken": token}),
        (ReviewRequest, {"attemptToken": token, "reviewId": review_id}),
    ):
        try:
            model.model_validate(base | extra)
        except ValidationError:
            continue
        raise AssertionError(f"{model.__name__} accepted extra fields {sorted(extra)}")
