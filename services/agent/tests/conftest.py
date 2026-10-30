import pytest
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

# One global tracer provider for the whole run (OpenTelemetry allows setting it once), keeping
# finished spans in memory so tests can look at them.
_SPANS = InMemorySpanExporter()
_provider = TracerProvider()
_provider.add_span_processor(SimpleSpanProcessor(_SPANS))
trace.set_tracer_provider(_provider)


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


@pytest.fixture
def spans() -> InMemorySpanExporter:
    _SPANS.clear()
    return _SPANS
