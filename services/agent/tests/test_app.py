import pytest
from fastapi.testclient import TestClient

from bankjob_agent.app import create_app
from bankjob_agent.settings import LlmMode, Settings


def test_healthz_reports_llm_mode() -> None:
    client = TestClient(create_app(Settings(llm_mode=LlmMode.STUB)))
    response = client.get("/healthz")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "llm_mode": "stub"}


def test_hello_identifies_the_service() -> None:
    client = TestClient(create_app(Settings(llm_mode=LlmMode.STUB)))
    assert client.get("/v1/hello").json()["service"] == "agent"


def test_llm_mode_defaults_to_stub(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("LLM_MODE", raising=False)
    assert Settings.from_env().llm_mode is LlmMode.STUB


def test_invalid_llm_mode_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("LLM_MODE", "yolo")
    with pytest.raises(ValueError, match="LLM_MODE"):
        Settings.from_env()
