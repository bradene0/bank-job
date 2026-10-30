"""One matrix run: which jobs to play, in what order, and the run's metadata."""

import math
import random
from dataclasses import dataclass, field
from typing import Any

from bankjob_evals.combos import combo_ids, load_tile_ids
from bankjob_evals.matrix import (
    CHAIN_SETUPS,
    SETUP_LEVEL,
    Job,
    ResolveDeferred,
    Row,
    Runner,
    Setup,
    choose_setup,
    play_until_done,
)

CHAIN_LEVEL = 5


@dataclass(frozen=True)
class Plan:
    levels: tuple[int, ...] = (1, 2, 3, 4, 5)
    setups: tuple[Setup, ...] = (Setup.FRESH, *CHAIN_SETUPS)
    sample: float | None = None  # fraction of combos per level, e.g. 0.1 for the nightly run
    sample_seed: str = "0"
    combos: tuple[str, ...] = field(default_factory=lambda: tuple(combo_ids(load_tile_ids())))

    def combos_for(self, level: int) -> list[str]:
        if self.sample is None:
            return list(self.combos)
        # A different, reproducible sample per level (the same seed gives the same sample).
        k = max(1, math.ceil(len(self.combos) * self.sample))
        picked = random.Random(f"{self.sample_seed}:{level}").sample(self.combos, k)  # noqa: S311
        return sorted(picked)

    def sample_json(self) -> dict[str, Any] | None:
        if self.sample is None:
            return None
        return {"fraction": self.sample, "seed": self.sample_seed}


@dataclass
class Result:
    rows: list[Row]
    setups: dict[str, Any]


async def run_plan(
    runner: Runner,
    plan: Plan,
    *,
    resolve_deferred: ResolveDeferred | None = None,
    max_rounds: int = 1,
) -> Result:
    """Fresh combos first, in parallel. Then, if asked, the Level 5 chain setups: each needs the
    fresh Level 4 results to pick the combo that sets it up."""
    fresh = [
        Job(Setup.FRESH, level, combo)
        for level in plan.levels
        for combo in plan.combos_for(level)
        if Setup.FRESH in plan.setups
    ]
    rows = await play_until_done(
        runner, fresh, resolve_deferred=resolve_deferred, max_rounds=max_rounds
    )

    setups: dict[str, Any] = {}
    chain: list[Job] = []
    if CHAIN_LEVEL in plan.levels:
        for setup in (s for s in plan.setups if s in CHAIN_SETUPS):
            if SETUP_LEVEL not in plan.levels or Setup.FRESH not in plan.setups:
                setups[setup.value] = {
                    "level": SETUP_LEVEL,
                    "comboId": None,
                    "note": "needs the fresh Level 4 results",
                }
                continue
            combo = choose_setup(rows, setup)
            setups[setup.value] = {"level": SETUP_LEVEL, "comboId": combo}
            if combo is None:
                setups[setup.value]["note"] = "no Level 4 combo in this run produced it"
                continue
            chain += [Job(setup, CHAIN_LEVEL, c, combo) for c in plan.combos_for(CHAIN_LEVEL)]
    if chain:
        rows += await play_until_done(
            runner, chain, resolve_deferred=resolve_deferred, max_rounds=max_rounds
        )
    return Result(rows, setups)
