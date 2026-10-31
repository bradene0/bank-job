import { useEffect, useMemo, useState, type CSSProperties } from "react";
import type { ApiClient } from "../api/client";
import type { Span, ToolCall } from "../api/types";
import type { Played } from "../game/state";

interface Props {
  played: Played;
  api: ApiClient;
  token: string | null;
  /** How often to ask for the trace while its spans arrive. */
  pollMs?: number;
}

/**
 * What actually happened: every tool call Penny made with the policy engine's decision, and the
 * attempt's trace from the browser through the agent and the ledger to the Kafka consumers.
 */
export function UnderTheHood({ played, api, token, pollMs = 1_000 }: Props) {
  return (
    <div className="under-the-hood">
      <p className="note">
        Everything below is what the bank&apos;s systems recorded. <code>BV-MULE-01</code> is your
        account.
      </p>
      <h3>Tool calls</h3>
      <ToolCallTimeline calls={played.toolCalls} />
      <h3>Trace</h3>
      {played.cached && (
        <p className="note">
          Someone played this exact con earlier today, so game-api did not call the model again: it
          replayed Penny&apos;s recorded decisions through the policy engine and your own ledger. The
          agent is not in this trace.
        </p>
      )}
      {played.source === "replay" ? (
        <Waterfall spans={played.recordedSpans} clientMs={null} pending={false} />
      ) : (
        <LiveTrace played={played} api={api} token={token} pollMs={pollMs} />
      )}
    </div>
  );
}

function ToolCallTimeline({ calls }: { calls: ToolCall[] }) {
  if (calls.length === 0) {
    return <p>Penny made no tool calls: she answered without touching the bank's systems.</p>;
  }
  return (
    <ol className="tool-calls" aria-label="Tool calls">
      {calls.map((call) => {
        const [code, ...rest] = call.reason.split(":");
        return (
          <li key={call.ordinal} data-decision={call.decision}>
            <span className="tool-name">{call.tool}</span>{" "}
            <span className={`decision decision-${call.decision}`}>
              {call.decision === "allow" ? "allowed" : "denied"}
            </span>{" "}
            {call.decision === "deny" && <code className="reason-code">{code}</code>}
            {rest.length > 0 && <span className="reason">{rest.join(":").trim()}</span>}
            <details className="tool-args">
              <summary>Arguments and result</summary>
              <pre>{JSON.stringify({ args: call.args, result: call.result, review: call.review }, null, 2)}</pre>
            </details>
          </li>
        );
      })}
    </ol>
  );
}

function LiveTrace({ played, api, token, pollMs }: Props & { pollMs: number }) {
  const [spans, setSpans] = useState<Span[]>([]);
  const [state, setState] = useState<"loading" | "complete" | "partial" | "unavailable">("loading");
  const traceId = played.traceId;

  useEffect(() => {
    if (!traceId || !token) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let tries = 0;
    const poll = async () => {
      tries += 1;
      try {
        const trace = await api.trace(token, traceId);
        if (cancelled) return;
        if (trace) {
          setSpans(trace.spans);
          if (trace.complete) {
            setState("complete");
            return;
          }
        }
      } catch {
        // keep trying below
      }
      if (cancelled) return;
      if (tries >= 20) {
        setState((s) => (s === "loading" ? "unavailable" : "partial"));
        return;
      }
      timer = setTimeout(poll, pollMs);
    };
    void poll();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [api, token, traceId, pollMs]);

  if (!traceId) return <p>This attempt was not traced.</p>;
  if (state === "unavailable" && spans.length === 0) return <p>The trace is not available right now.</p>;
  return (
    <>
      <p className="trace-id">
        Trace <code>{traceId}</code>
      </p>
      <Waterfall spans={spans} clientMs={played.clientMs} pending={state === "loading"} />
    </>
  );
}

const NOISE = /^(query|.* http (send|receive))$/;

function depthOf(span: Span, byId: Map<string, Span>): number {
  let depth = 0;
  for (let p = span.parentSpanId ? byId.get(span.parentSpanId) : undefined; p && depth < 30; depth++) {
    p = p.parentSpanId ? byId.get(p.parentSpanId) : undefined;
  }
  return depth;
}

function Waterfall({ spans, clientMs, pending }: { spans: Span[]; clientMs: number | null; pending: boolean }) {
  const [showAll, setShowAll] = useState(false);
  const byId = useMemo(() => new Map(spans.map((s) => [s.spanId, s])), [spans]);
  const shown = showAll ? spans : spans.filter((s) => !NOISE.test(s.name));
  const total = Math.max(1, ...spans.map((s) => s.startOffsetMs + s.durationMs));
  const services = [...new Set(spans.map((s) => s.service))];

  return (
    <div className="waterfall">
      <p role="status" className="trace-status">
        {pending ? "Collecting spans… " : ""}
        {spans.length} spans from {services.join(" and ") || "no services yet"}
        {clientMs !== null && `; the browser saw a ${Math.round(clientMs)} ms round trip`}
      </p>
      {spans.length > 0 && (
        <label className="toggle">
          <input type="checkbox" checked={showAll} onChange={(e) => setShowAll(e.target.checked)} /> Show
          SQL and connection-level spans ({spans.length - spans.filter((s) => !NOISE.test(s.name)).length})
        </label>
      )}
      <ol className="spans" aria-label="Spans">
        {shown.map((span) => (
          <li
            key={span.spanId}
            className="span"
            data-service={span.service}
            data-error={span.error ? "true" : undefined}
            style={{ "--depth": depthOf(span, byId) } as CSSProperties}
          >
            <span className="span-name">
              <span className="span-service">{span.service}</span> {span.name}
              {span.attributes["bankjob.policy.decision"] !== undefined && (
                <span className={`decision decision-${String(span.attributes["bankjob.policy.decision"])}`}>
                  {" "}
                  {String(span.attributes["bankjob.policy.reason_code"] ?? span.attributes["bankjob.policy.decision"])}
                </span>
              )}
            </span>
            <span className="span-time">
              {span.durationMs.toFixed(1)} ms at +{span.startOffsetMs.toFixed(1)} ms
            </span>
            <span className="span-track" aria-hidden="true">
              <span
                className="span-bar"
                style={{
                  left: `${(span.startOffsetMs / total) * 100}%`,
                  width: `${Math.max(0.5, (span.durationMs / total) * 100)}%`,
                }}
              />
            </span>
          </li>
        ))}
      </ol>
    </div>
  );
}
