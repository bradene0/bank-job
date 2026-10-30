import os
from dataclasses import dataclass
from enum import StrEnum


class LlmMode(StrEnum):
    STUB = "stub"  # scripted teller behavior; never calls the Anthropic API
    LIVE = "live"


DEFAULT_MODEL = "claude-haiku-5-5"
DEFAULT_MCP_URL = "http://game-api:8080/mcp"


@dataclass(frozen=True)
class Settings:
    llm_mode: LlmMode
    anthropic_model: str = DEFAULT_MODEL
    mcp_url: str = DEFAULT_MCP_URL
    max_tool_calls: int = 8
    llm_timeout_seconds: float = 20.0
    # Only for tests: point the SDK at a local fake of the Messages API.
    anthropic_base_url: str | None = None
    # OTLP/HTTP collector base URL (Jaeger, ADR-0007); unset means no span export.
    otlp_endpoint: str | None = None

    @property
    def model_label(self) -> str:
        """The model reported in responses; "stub" when no model is involved."""
        return self.anthropic_model if self.llm_mode is LlmMode.LIVE else "stub"

    @classmethod
    def from_env(cls) -> Settings:
        # Default to stub so a missing variable can never spend tokens.
        raw = os.environ.get("LLM_MODE", LlmMode.STUB).strip().lower()
        try:
            mode = LlmMode(raw)
        except ValueError:
            allowed = ", ".join(m.value for m in LlmMode)
            raise ValueError(f"LLM_MODE must be one of: {allowed}; got {raw!r}") from None

        # Name the variable, never echo its value.
        if mode is LlmMode.LIVE and not os.environ.get("ANTHROPIC_API_KEY", "").strip():
            raise ValueError("ANTHROPIC_API_KEY must be set when LLM_MODE=live")

        return cls(
            llm_mode=mode,
            anthropic_model=os.environ.get("ANTHROPIC_MODEL", "").strip() or DEFAULT_MODEL,
            mcp_url=os.environ.get("GAME_API_MCP_URL", "").strip() or DEFAULT_MCP_URL,
            max_tool_calls=_positive_int("PENNY_MAX_TOOL_CALLS", 8),
            llm_timeout_seconds=_positive_float("LLM_TIMEOUT_SECONDS", 20.0),
            anthropic_base_url=os.environ.get("ANTHROPIC_BASE_URL", "").strip() or None,
            otlp_endpoint=os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT", "").strip() or None,
        )


def _positive_int(name: str, default: int) -> int:
    raw = os.environ.get(name, "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        raise ValueError(f"{name} must be a positive integer; got {raw!r}") from None
    if value < 1:
        raise ValueError(f"{name} must be a positive integer; got {raw!r}")
    return value


def _positive_float(name: str, default: float) -> float:
    raw = os.environ.get(name, "").strip()
    if not raw:
        return default
    try:
        value = float(raw)
    except ValueError:
        raise ValueError(f"{name} must be a positive number; got {raw!r}") from None
    if not value > 0:
        raise ValueError(f"{name} must be a positive number; got {raw!r}")
    return value
