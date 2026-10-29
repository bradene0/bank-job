# syntax=docker/dockerfile:1
ARG PYTHON=python:3.14.10-slim-trixie
ARG UV=ghcr.io/astral-sh/uv:0.12.18

FROM ${UV} AS uv

FROM ${PYTHON} AS build
COPY --from=uv /uv /usr/local/bin/uv
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv uv sync --locked --no-dev --no-install-project
COPY src/ src/
RUN --mount=type=cache,target=/root/.cache/uv uv sync --locked --no-dev --no-editable

FROM ${PYTHON} AS runtime
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /app/.venv /app/.venv
ENV PATH="/app/.venv/bin:$PATH" PYTHONUNBUFFERED=1
USER 10001
EXPOSE 8000
CMD ["uvicorn", "bankjob_agent.app:app", "--host", "0.0.0.0", "--port", "8000"]
