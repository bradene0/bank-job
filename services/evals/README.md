# evals (Python 3.14)

Attack-matrix harness (864 combos per level), deterministic grading, replay-file generator,
regression cases. Built in M5. M0 ships combo enumeration from `content/tiles.yaml`.

```
uv sync --locked
uv run ruff check . && uv run ruff format --check . && uv run mypy src tests
uv run pytest
```
