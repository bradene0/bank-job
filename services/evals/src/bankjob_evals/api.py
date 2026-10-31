"""A client for the public game API, used exactly the way the browser uses it.

Sessions, attempts (tile ids only, ADR-0003) and traces. Each attempt carries a W3C
`traceparent` the harness generated, as the game does (ADR-0015), so its trace can be read back.
"""

import asyncio
import secrets
import time
from collections.abc import Callable
from typing import Any

import httpx2

SLOTS = ("persona", "pretext", "technique", "payload")

# Reading a trace goes through game-api to Jaeger's query API, which can briefly answer 502-504
# under matrix load (its in-memory store is busy). Those reads are retried with backoff.
TRANSIENT = frozenset({502, 503, 504})
TRACE_RETRY_DELAYS = (0.25, 0.5, 1.0, 2.0, 4.0)


class ApiError(Exception):
    def __init__(self, status: int, detail: str) -> None:
        super().__init__(f"HTTP {status}: {detail}")
        self.status = status
        self.detail = detail


class LiveUnavailable(ApiError):
    """503 with `replay: true`: the agent failed or the model budget is spent. Nerve is refunded."""

    def __init__(self, reason: str) -> None:
        super().__init__(503, reason)
        self.reason = reason


def new_traceparent() -> str:
    return f"00-{secrets.token_hex(16)}-{secrets.token_hex(8)}-01"


def trace_id_of(traceparent: str) -> str:
    return traceparent.split("-")[1]


def tiles_of(combo_id: str) -> dict[str, str]:
    return dict(zip(SLOTS, combo_id.split("."), strict=True))


class GameApi:
    def __init__(
        self,
        base_url: str,
        *,
        timeout: float = 90.0,
        transport: httpx2.AsyncBaseTransport | None = None,
        trace_retry_delays: tuple[float, ...] = TRACE_RETRY_DELAYS,
    ) -> None:
        self._http = httpx2.AsyncClient(base_url=base_url, timeout=timeout, transport=transport)
        self._trace_retry_delays = trace_retry_delays

    async def aclose(self) -> None:
        await self._http.aclose()

    async def new_session(self) -> str:
        response = await self._http.post("/v1/sessions")
        if response.status_code == 429:
            raise ApiError(
                429,
                "session rate limit: raise SESSIONS_MAX_PER_IP_PER_HOUR on game-api"
                f" (Retry-After {response.headers.get('retry-after')} s)",
            )
        _check(response)
        token: str = response.json()["token"]
        return token

    async def attempt(
        self, token: str, level: int, combo_id: str, traceparent: str
    ) -> dict[str, Any]:
        response = await self._http.post(
            "/v1/attempts",
            json={"level": level, "tiles": tiles_of(combo_id)},
            headers={"Authorization": f"Bearer {token}", "traceparent": traceparent},
        )
        if response.status_code == 503:
            body = _json_or_empty(response)
            if body.get("replay") is True:
                raise LiveUnavailable(str(body.get("reason", "unknown")))
        _check(response)
        result: dict[str, Any] = response.json()
        return result

    async def trace(self, token: str, trace_id: str) -> dict[str, Any]:
        for delay in (*self._trace_retry_delays, None):
            response = await self._http.get(
                f"/v1/traces/{trace_id}", headers={"Authorization": f"Bearer {token}"}
            )
            if response.status_code not in TRANSIENT or delay is None:
                break
            await asyncio.sleep(delay)
        _check(response)
        result: dict[str, Any] = response.json()
        return result

    async def wait_for_trace(
        self,
        token: str,
        trace_id: str,
        until: Callable[[dict[str, Any]], bool],
        *,
        timeout: float = 15.0,
        interval: float = 0.05,
    ) -> dict[str, Any]:
        """Polls GET /v1/traces until `until(trace)` holds; the Kafka consumers run after the
        attempt returns, so a trace fills in for a moment. Returns the last trace on timeout."""
        deadline = time.monotonic() + timeout
        while True:
            try:
                trace = await self.trace(token, trace_id)
            except ApiError as e:
                if e.status != 404:  # 404 until the first spans reach Jaeger
                    raise
                trace = {"traceId": trace_id, "complete": False, "spans": []}
            if until(trace) or time.monotonic() >= deadline:
                return trace
            await asyncio.sleep(interval)


def _json_or_empty(response: httpx2.Response) -> dict[str, Any]:
    try:
        body = response.json()
    except ValueError:
        return {}
    return body if isinstance(body, dict) else {}


def _check(response: httpx2.Response) -> None:
    if response.status_code >= 400:
        body = _json_or_empty(response)
        detail = body.get("detail") or body.get("title") or response.text[:200]
        raise ApiError(response.status_code, str(detail))
