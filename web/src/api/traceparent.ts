// The browser starts each attempt's trace: game-api continues this W3C traceparent, so the
// trace id is known before the request is sent (ADR-0015).

function randomHex(bytes: number): string {
  const buffer = new Uint8Array(bytes);
  crypto.getRandomValues(buffer);
  return Array.from(buffer, (b) => b.toString(16).padStart(2, "0")).join("");
}

export interface TraceContext {
  traceId: string;
  spanId: string;
  header: string;
}

export function newTraceContext(): TraceContext {
  let traceId = randomHex(16);
  let spanId = randomHex(8);
  // All-zero ids are invalid in W3C trace context (vanishingly unlikely, but cheap to rule out).
  while (/^0+$/.test(traceId)) traceId = randomHex(16);
  while (/^0+$/.test(spanId)) spanId = randomHex(8);
  return { traceId, spanId, header: `00-${traceId}-${spanId}-01` };
}
