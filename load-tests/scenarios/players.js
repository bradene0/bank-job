// Players against the local compose stack in stub mode (LLM_MODE=stub: a scripted Penny, no
// model calls, no token spend). Each virtual player does what the game does: open a guest
// session, then play calls level by level (tile ids only, a traceparent per call), read one
// call's trace for "Under the hood", and look at the leaderboard.
//
//   PROFILE=steady  20 players for 3 minutes, a second or two between calls (the SLO check)
//   PROFILE=stress  ramp to 120 players with no think time, to find where it bends
import http from "k6/http";
import { check, sleep } from "k6";
import { Counter } from "k6/metrics";

const BASE = __ENV.BASE_URL || "http://game-api:8080";
const PROFILE = __ENV.PROFILE || "steady";

const PROFILES = {
  steady: {
    executor: "ramping-vus",
    stages: [
      { duration: "30s", target: 20 },
      { duration: "3m", target: 20 },
      { duration: "15s", target: 0 },
    ],
  },
  stress: {
    executor: "ramping-vus",
    stages: [
      { duration: "1m", target: 40 },
      { duration: "1m", target: 80 },
      { duration: "1m", target: 120 },
      { duration: "15s", target: 0 },
    ],
  },
};

export const options = {
  scenarios: { players: PROFILES[PROFILE] },
  // The SLO: p95 of POST /v1/attempts under 150 ms without model time (stub mode).
  // (k6 reports a tagged sub-metric only when it has a threshold, so every name gets one.)
  thresholds: {
    "http_req_duration{name:attempt}": ["p(95)<150"],
    "http_req_failed{name:attempt}": ["rate<0.01"],
    "http_reqs{name:attempt}": ["count>0"],
    "http_req_duration{name:session}": ["p(95)<150"],
    "http_req_failed{name:session}": ["rate<0.01"],
    "http_reqs{name:session}": ["count>0"],
    "http_req_duration{name:trace}": ["p(95)<1000"],
    "http_req_failed{name:trace}": ["rate<0.05"],
    "http_reqs{name:trace}": ["count>0"],
    "http_req_duration{name:leaderboard}": ["p(95)<150"],
    "http_req_failed{name:leaderboard}": ["rate<0.01"],
    "http_reqs{name:leaderboard}": ["count>0"],
    "outcomes{cached:true}": ["count>=0"],
    "outcomes{cached:false}": ["count>=0"],
  },
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

const outcomes = new Counter("outcomes");
const PERSONAS = ["p1", "p2", "p3", "p4", "p5", "p6"];
const PRETEXTS = ["x1", "x2", "x3", "x4", "x5", "x6"];
const TECHNIQUES = ["t1", "t2", "t3", "t4", "t5", "t6"];
const PAYLOADS = ["y1", "y2", "y3", "y4"];
const pick = (xs) => xs[Math.floor(Math.random() * xs.length)];
const hex = (n) => Array.from({ length: n }, () => Math.floor(Math.random() * 16).toString(16)).join("");

// Real players repeat the obvious cons, so half the calls come from a small popular set (which the
// response cache answers after the first play, ADR-0012) and half from anywhere in the matrix.
const POPULAR = Array.from({ length: 24 }, () => [pick(PERSONAS), pick(PRETEXTS), pick(TECHNIQUES), pick(PAYLOADS)]);
const con = () => (Math.random() < 0.5 ? pick(POPULAR) : [pick(PERSONAS), pick(PRETEXTS), pick(TECHNIQUES), pick(PAYLOADS)]);
const think = () => (PROFILE === "steady" ? sleep(1 + Math.random()) : undefined);

export default function () {
  const session = http.post(`${BASE}/v1/sessions`, null, { tags: { name: "session" } });
  if (!check(session, { "session created": (r) => r.status === 201 })) return;
  const auth = { Authorization: `Bearer ${session.json("token")}`, "Content-Type": "application/json" };
  let traced = null;

  for (const level of [1, 2, 3, 4, 5]) {
    for (let i = 0; i < 3; i++) {
      const [persona, pretext, technique, payload] = con();
      const traceId = hex(32);
      const res = http.post(
        `${BASE}/v1/attempts`,
        JSON.stringify({ level, tiles: { persona, pretext, technique, payload } }),
        { headers: { ...auth, traceparent: `00-${traceId}-${hex(16)}-01` }, tags: { name: "attempt" } },
      );
      if (check(res, { "attempt answered": (r) => r.status === 200 })) {
        outcomes.add(1, { outcome: res.json("outcome"), cached: String(res.json("cached")) });
        traced = traced || traceId;
      }
      think();
    }
  }

  if (traced) {
    http.get(`${BASE}/v1/traces/${traced}`, { headers: auth, tags: { name: "trace" } });
  }
  http.get(`${BASE}/v1/leaderboard`, { tags: { name: "leaderboard" } });
}

export function handleSummary(data) {
  const out = __ENV.OUT || `/results/${PROFILE}`;
  return { [`${out}.json`]: JSON.stringify(data, null, 2), stdout: summaryText(data) };
}

function summaryText(data) {
  const row = (name) => {
    const m = data.metrics[`http_req_duration{name:${name}}`];
    const f = data.metrics[`http_req_failed{name:${name}}`];
    if (!m) return `${name}: no requests`;
    const v = m.values;
    return `${name}: n=${data.metrics[`http_reqs{name:${name}}`]?.values.count ?? "?"} p50=${v.med.toFixed(1)}ms p95=${v["p(95)"].toFixed(1)}ms p99=${v["p(99)"].toFixed(1)}ms max=${v.max.toFixed(0)}ms failed=${f ? (f.values.rate * 100).toFixed(2) : "?"}%`;
  };
  const reqs = data.metrics.http_reqs.values;
  const hits = data.metrics["outcomes{cached:true}"]?.values.count ?? 0;
  const misses = data.metrics["outcomes{cached:false}"]?.values.count ?? 0;
  return [
    `profile=${PROFILE} requests=${reqs.count} rate=${reqs.rate.toFixed(1)}/s peak players=${data.metrics.vus_max.values.max}`,
    `attempts answered from the response cache: ${hits} of ${hits + misses}`,
    row("attempt"),
    row("session"),
    row("trace"),
    row("leaderboard"),
    "",
  ].join("\n");
}
