# load-tests (k6)

k6 players against the local compose stack in stub mode (`LLM_MODE=stub`: a scripted Penny, no
model calls, no token spend). Production is never load-tested: it is one homelab VM, and the
model is the expensive part.

Each virtual player does what the game does: open a guest session, play three calls on each of
the five levels (tile ids and a `traceparent`, as the browser sends them), read one call's trace
for "Under the hood", and look at the leaderboard. Half the calls come from a small set of popular
cons, which the response cache answers after their first play (ADR-0012); half come from
anywhere in the 864-con matrix.

```
SESSIONS_MAX_PER_IP_PER_HOUR=1000000 docker compose up -d --wait
docker compose exec -T postgres psql -U bankjob -d bankjob -c 'DELETE FROM attempt_cache'   # start cold
load-tests/run.sh steady     # 20 players, 1-2 s between calls, 3 minutes: the SLO check
load-tests/run.sh stress     # ramp to 120 players with no think time: where it bends
```

`run.sh` refuses to run unless game-api is in stub mode. The full k6 summaries are in
`results/`.

## Results (2026-10-30)

Apple M5, Docker with 10 CPUs and 8 GiB; compose stack as committed (game-api on the JVM build
with 512 MiB, the agent in stub mode, Postgres, Kafka, Jaeger). Response cache emptied before each
run.

| Profile | Players | Requests | Attempts | From the cache | Attempt p50 / p95 / p99 | Errors |
|---|---|---|---|---|---|---|
| steady | 20 | 3,402 (13.9/s) | 2,835 | 943 (33%) | 24.5 / **94.0** / 113.7 ms | 0 |
| stress | ramp to 120 | 44,892 (230/s) | 37,410 (~190/s) | 33,066 (88%) | 209 / 936 / 1,697 ms | 0 |

Other endpoints, steady: session p95 11.1 ms, trace read p95 6.4 ms, leaderboard p95 1.3 ms.

**SLO** (p95 `POST /v1/attempts` under 150 ms without model time): **met** at 94 ms with two of
three attempts running the agent.

**Stress.** With 120 players calling back to back, the stack still answered every request (0
errors), but attempt latency grew to a p95 of 0.94 s: game-api saturated its CPU (peaks around
6.7 cores; Postgres and Kafka about 1.6 cores each, the stub agent 1.1). A real player makes about
one call every 20-30 seconds (four props, the bell, Penny's three-second performance), so 190
attempts a second is the traffic of several thousand people playing at once. Past that, the next
steps would be the native image (production runs it), more game-api memory, and a second
replica; the outbox and the idempotent consumers already allow more than one.

These numbers exclude model time on purpose. A live attempt adds Penny's model calls (a few
seconds); the response cache and the budget switch keep that cost bounded.
