"""What game-api's MCP server tells the agent about an attempt or a pending review.

Unknown fields are ignored so game-api can add fields without breaking a deployed agent;
required fields are validated so a malformed brief fails before any model call.
"""

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel


class _Wire(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel, validate_by_name=True, extra="ignore", frozen=True
    )


class Tiles(_Wire):
    persona: Annotated[str, Field(pattern=r"^p[1-6]$")]
    pretext: Annotated[str, Field(pattern=r"^x[1-6]$")]
    technique: Annotated[str, Field(pattern=r"^t[1-6]$")]
    payload: Annotated[str, Field(pattern=r"^y[1-4]$")]


class StubFacts(_Wire):
    """Structured facts the scripted stub teller reads instead of prose. Live mode ignores them."""

    wary_persona: Annotated[str, Field(pattern=r"^p[1-6]$")]
    caller_phone: str
    document_ids: list[str] = Field(default_factory=list)
    payload_amount_cents: Annotated[int, Field(gt=0)] | None = None


class AttemptBrief(_Wire):
    attempt_id: str
    level: Annotated[int, Field(ge=1, le=5)]
    combo_id: Annotated[str, Field(pattern=r"^p[1-6]\.x[1-6]\.t[1-6]\.y[1-4]$")]
    tiles: Tiles
    system: Annotated[str, Field(min_length=1)]
    message: Annotated[str, Field(min_length=1)]
    max_tool_calls: Annotated[int, Field(ge=1, le=50)]
    stub: StubFacts

    @model_validator(mode="after")
    def _combo_matches_tiles(self) -> AttemptBrief:
        t = self.tiles
        if self.combo_id != f"{t.persona}.{t.pretext}.{t.technique}.{t.payload}":
            raise ValueError("comboId does not match tiles")
        return self


class ReviewFacts(_Wire):
    tool: str
    to_account: str | None = None
    documents_read: list[str] = Field(default_factory=list)
    document_claims_clearance: bool = False


class ReviewBrief(_Wire):
    review_id: str
    system: Annotated[str, Field(min_length=1)]
    content: Annotated[str, Field(min_length=1)]
    stub: ReviewFacts


Verdict = Literal["allow", "deny"]
