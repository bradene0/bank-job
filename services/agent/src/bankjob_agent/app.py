import logging
from collections.abc import AsyncIterator, Callable
from contextlib import AbstractAsyncContextManager, asynccontextmanager
from typing import Annotated, Literal
from uuid import UUID

import anthropic
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from bankjob_agent import bank
from bankjob_agent.bank import Bank, BankUnavailable, TokenRejected
from bankjob_agent.guardrail import ClaudeGuardrail, Guardrail, StubGuardrail
from bankjob_agent.penny import ClaudeTeller, StubTeller, Teller
from bankjob_agent.settings import LlmMode, Settings

log = logging.getLogger("bankjob_agent")

BankConnector = Callable[[str], AbstractAsyncContextManager[Bank]]

# The only input the agent accepts: a JWT (base64url segments joined by dots) and ids. No text.
AttemptToken = Annotated[str, Field(min_length=20, max_length=4096, pattern=r"^[A-Za-z0-9_\-.]+$")]


class _Request(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, extra="forbid")


class RunRequest(_Request):
    attempt_token: AttemptToken


class ReviewRequest(_Request):
    attempt_token: AttemptToken
    review_id: UUID


class _Response(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel, validate_by_name=True, serialize_by_alias=True
    )


class Usage(_Response):
    input_tokens: int
    output_tokens: int


class RunResponse(_Response):
    reply: str
    stop_reason: str
    model: str
    tool_calls: int
    usage: Usage


class ReviewResponse(_Response):
    verdict: Literal["allow", "deny"]
    reason: str
    model: str
    usage: Usage


def _problem(status: int, title: str) -> JSONResponse:
    return JSONResponse({"title": title, "status": status}, status_code=status)


def create_app(
    settings: Settings | None = None,
    *,
    teller: Teller | None = None,
    guardrail: Guardrail | None = None,
    connect_bank: BankConnector | None = None,
) -> FastAPI:
    settings = settings or Settings.from_env()
    llm: anthropic.AsyncAnthropic | None = None
    if settings.llm_mode is LlmMode.LIVE and (teller is None or guardrail is None):
        # The key comes from ANTHROPIC_API_KEY; Settings already checked it is set.
        llm = anthropic.AsyncAnthropic(
            base_url=settings.anthropic_base_url,
            max_retries=1,
            timeout=settings.llm_timeout_seconds,
        )
    if teller is None:
        teller = StubTeller() if llm is None else ClaudeTeller(llm, settings.anthropic_model)
    if guardrail is None:
        guardrail = (
            StubGuardrail() if llm is None else ClaudeGuardrail(llm, settings.anthropic_model)
        )
    if connect_bank is None:

        def connect_bank(token: str) -> AbstractAsyncContextManager[Bank]:
            return bank.connect(settings.mcp_url, token)

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        yield
        if llm is not None:
            await llm.close()

    app = FastAPI(title="Bank Job agent", version="0.2.0", lifespan=lifespan)

    @app.exception_handler(TokenRejected)
    async def token_rejected(_: object, __: Exception) -> JSONResponse:
        return _problem(401, "Attempt token rejected")

    @app.exception_handler(BankUnavailable)
    async def bank_unavailable(_: object, e: Exception) -> JSONResponse:
        log.warning("MCP server failed: %s", e)
        return _problem(502, "Bank tools unavailable")

    @app.exception_handler(anthropic.APIError)
    async def model_failed(_: object, e: Exception) -> JSONResponse:
        # Class name only: SDK messages can echo request details.
        log.warning("Model call failed: %s", type(e).__name__)
        return _problem(502, "Model unavailable")

    @app.get("/healthz")
    def healthz() -> dict[str, str]:
        return {"status": "ok", "llm_mode": settings.llm_mode, "model": settings.model_label}

    @app.get("/v1/hello")
    def hello() -> dict[str, str]:
        return {"service": "agent", "message": "Penny is warming up her smile."}

    @app.post("/v1/runs")
    async def run(request: RunRequest) -> RunResponse:
        async with connect_bank(request.attempt_token) as session:
            brief = await session.brief()
            result = await teller.run(session, brief)
        return RunResponse(
            reply=result.reply,
            stop_reason=result.stop_reason,
            model=result.model,
            tool_calls=result.tool_calls,
            usage=Usage(input_tokens=result.input_tokens, output_tokens=result.output_tokens),
        )

    @app.post("/v1/reviews")
    async def review(request: ReviewRequest) -> ReviewResponse:
        async with connect_bank(request.attempt_token) as session:
            brief = await session.review(str(request.review_id))
        result = await guardrail.review(brief)
        return ReviewResponse(
            verdict=result.verdict,
            reason=result.reason,
            model=result.model,
            usage=Usage(input_tokens=result.input_tokens, output_tokens=result.output_tokens),
        )

    return app


app = create_app()
