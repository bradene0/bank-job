import os
from dataclasses import dataclass
from enum import StrEnum


class LlmMode(StrEnum):
    STUB = "stub"  # scripted teller behavior; never calls the Anthropic API
    LIVE = "live"


@dataclass(frozen=True)
class Settings:
    llm_mode: LlmMode

    @classmethod
    def from_env(cls) -> Settings:
        # Default to stub so a missing variable can never spend tokens.
        raw = os.environ.get("LLM_MODE", LlmMode.STUB).strip().lower()
        try:
            mode = LlmMode(raw)
        except ValueError:
            allowed = ", ".join(m.value for m in LlmMode)
            raise ValueError(f"LLM_MODE must be one of: {allowed}; got {raw!r}") from None
        return cls(llm_mode=mode)
