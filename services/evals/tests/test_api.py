import pytest

from bankjob_evals.api import ApiError, GameApi
from bankjob_evals.matrix import Runner, RunOptions, Setup
from bankjob_evals.run import Plan, run_plan
from tests.fake_api import FakeGameApi

pytestmark = pytest.mark.anyio

NO_WAIT = (0.0, 0.0, 0.0)


def api_for(fake: FakeGameApi) -> GameApi:
    return GameApi("http://game-api.test", transport=fake.transport(), trace_retry_delays=NO_WAIT)


async def test_a_replay_survives_a_brief_jaeger_outage() -> None:
    fake = FakeGameApi()
    fake.trace_failures = 2
    runner = Runner(api_for(fake), RunOptions(concurrency=1, progress=False))
    result = await run_plan(
        runner, Plan(levels=(1,), setups=(Setup.FRESH,), combos=("p1.x1.t2.y1",))
    )

    assert result.rows[0].replay is not None
    assert fake.trace_failures == 0


async def test_a_trace_read_gives_up_after_its_retries() -> None:
    fake = FakeGameApi()
    fake.trace_failures = 10
    api = api_for(fake)
    token = await api.new_session()
    with pytest.raises(ApiError) as failure:
        await api.trace(token, "0" * 32)
    assert failure.value.status == 503
    assert fake.trace_failures == 6  # four reads: the first and three retries
