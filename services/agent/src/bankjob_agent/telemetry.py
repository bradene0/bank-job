"""OpenTelemetry for the agent: its spans join the attempt's trace started in game-api.

- game-api calls /v1/runs and /v1/reviews with a `traceparent` header; the FastAPI
  instrumentation continues that trace.
- The MCP SDK opens a client span per request (`MCP send tools/call <tool>`).
  `inject_traceparent` puts the current span into each HTTP request to game-api, so game-api's
  spans for a tool call nest under it.
- Model calls get `chat <model>` spans with the GenAI semantic conventions: model, token usage
  and finish reason. Prompts and replies are never recorded on spans.
"""

from collections.abc import Mapping
from typing import Any

import httpx2
from anthropic import AsyncAnthropic
from anthropic.types import Message
from fastapi import FastAPI
from opentelemetry import propagate, trace
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.trace import SpanKind
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator

SERVICE_NAME = "agent"
tracer = trace.get_tracer("bankjob_agent")


def configure(otlp_endpoint: str | None) -> TracerProvider | None:
    """Exports spans to the OTLP/HTTP endpoint (Jaeger, ADR-0007). Without one, the API no-ops."""
    # W3C trace context only: no baggage, as in game-api. A caller's baggage is not relayed.
    propagate.set_global_textmap(TraceContextTextMapPropagator())
    if not otlp_endpoint:
        return None
    provider = TracerProvider(resource=Resource.create({"service.name": SERVICE_NAME}))
    exporter = OTLPSpanExporter(endpoint=otlp_endpoint.rstrip("/") + "/v1/traces")
    provider.add_span_processor(BatchSpanProcessor(exporter, schedule_delay_millis=1000))
    trace.set_tracer_provider(provider)
    return provider


def instrument(app: FastAPI, provider: TracerProvider | None) -> None:
    # Health probes would each start a one-span trace.
    FastAPIInstrumentor.instrument_app(app, tracer_provider=provider, excluded_urls="healthz")


async def inject_traceparent(request: httpx2.Request) -> None:
    """httpx2 request hook: carry the current span to game-api."""
    propagate.inject(request.headers)


async def traced_model_call(
    client: AsyncAnthropic, *, model: str, attributes: Mapping[str, Any], **request: Any
) -> Message:
    """`client.messages.create(...)` inside a GenAI `chat` span."""
    with tracer.start_as_current_span(
        f"chat {model}",
        kind=SpanKind.CLIENT,
        attributes={
            "gen_ai.operation.name": "chat",
            "gen_ai.provider.name": "anthropic",
            "gen_ai.request.model": model,
            **attributes,
        },
    ) as span:
        response: Message = await client.messages.create(model=model, **request)
        span.set_attribute("gen_ai.response.model", response.model)
        span.set_attribute("gen_ai.response.finish_reasons", [str(response.stop_reason)])
        span.set_attribute("gen_ai.usage.input_tokens", response.usage.input_tokens)
        span.set_attribute("gen_ai.usage.output_tokens", response.usage.output_tokens)
        return response
