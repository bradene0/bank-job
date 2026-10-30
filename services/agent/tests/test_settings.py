import pytest

from bankjob_agent.settings import LlmMode, Settings


@pytest.fixture(autouse=True)
def clean_env(monkeypatch: pytest.MonkeyPatch) -> None:
    for name in (
        "LLM_MODE",
        "ANTHROPIC_API_KEY",
        "ANTHROPIC_MODEL",
        "GAME_API_MCP_URL",
        "PENNY_MAX_TOOL_CALLS",
        "LLM_TIMEOUT_SECONDS",
        "ANTHROPIC_BASE_URL",
    ):
        monkeypatch.delenv(name, raising=False)


def test_defaults_are_stub_and_local_compose() -> None:
    settings = Settings.from_env()
    assert settings.llm_mode is LlmMode.STUB
    assert settings.anthropic_model == "claude-haiku-5-5"
    assert settings.mcp_url == "http://game-api:8080/mcp"
    assert settings.max_tool_calls == 8
    assert settings.model_label == "stub"


def test_invalid_llm_mode_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("LLM_MODE", "yolo")
    with pytest.raises(ValueError, match="LLM_MODE"):
        Settings.from_env()


def test_live_mode_requires_an_api_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("LLM_MODE", "live")
    with pytest.raises(ValueError, match="ANTHROPIC_API_KEY"):
        Settings.from_env()


def test_live_mode_never_echoes_the_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("LLM_MODE", "live")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test-not-a-real-key")
    monkeypatch.setenv("PENNY_MAX_TOOL_CALLS", "0")
    with pytest.raises(ValueError) as error:
        Settings.from_env()
    assert "sk-test" not in str(error.value)


def test_live_mode_reports_the_model(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("LLM_MODE", "LIVE")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test-not-a-real-key")
    monkeypatch.setenv("PENNY_MAX_TOOL_CALLS", "5")
    settings = Settings.from_env()
    assert (settings.llm_mode, settings.model_label, settings.max_tool_calls) == (
        LlmMode.LIVE,
        "claude-haiku-5-5",
        5,
    )
