import { useEffect, useState, useSyncExternalStore } from "react";
import type { ToolCall } from "../api/types";
import type { Pose, ToolPose } from "../components/scene/Penny";
import type { Played } from "./state";

/** One moment of Penny's performance: her pose, what she says, and which tool is running. */
export interface Beat {
  pose: Pose;
  line: string;
  tool?: string;
  decision?: "allow" | "deny";
  /** The Level 5 guardrail agent reviewed this call. */
  reviewing: boolean;
}

const TOOLS: Record<string, { pose: ToolPose; line: string }> = {
  lookup_account: { pose: "lookup", line: "Let me look up that account…" },
  get_balance: { pose: "balance", line: "Checking the balance…" },
  verify_identity: { pose: "verify", line: "Just checking who you are…" },
  read_document: { pose: "document", line: "Reading your document…" },
  update_contact_info: { pose: "phone", line: "Updating the phone number on file…" },
  transfer_funds: { pose: "transfer", line: "Keying in the wire…" },
  flag_for_review: { pose: "flag", line: "I'm flagging this for a supervisor." },
};

export const IDLE: Beat = { pose: "idle", line: "", reviewing: false };
export const THINKING: Beat = { pose: "thinking", line: "One moment, let me look into that…", reviewing: false };

export function beatFor(call: ToolCall): Beat {
  const tool = TOOLS[call.tool] ?? { pose: "thinking" as const, line: "Working on it…" };
  return {
    pose: tool.pose,
    line: call.decision === "deny" ? `${tool.line} Oh. The bank's controls won't let me.` : tool.line,
    tool: call.tool,
    decision: call.decision,
    reviewing: call.review !== null,
  };
}

const QUERY = "(prefers-reduced-motion: reduce)";

function subscribe(onChange: () => void) {
  if (typeof window.matchMedia !== "function") return () => undefined;
  const media = window.matchMedia(QUERY);
  media.addEventListener("change", onChange);
  return () => media.removeEventListener("change", onChange);
}

export function useReducedMotion(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => typeof window.matchMedia === "function" && window.matchMedia(QUERY).matches,
    () => false,
  );
}

/** How long Penny spends on each tool call: the whole show stays around three seconds. */
export function stepFor(calls: number): number {
  return Math.min(900, Math.max(500, Math.round(3_000 / Math.max(1, calls))));
}

/**
 * Plays an attempt back as a short performance: Penny thinks while the call runs, then acts out
 * each tool call in order (with the policy engine's decision), then strikes the outcome pose.
 * The result is revealed when the performance ends. With reduced motion, or stepMs 0, it skips
 * straight to the outcome: same poses, no waiting.
 */
export function usePennyScript(playing: boolean, played: Played | null, stepMs?: number) {
  const reduced = useReducedMotion();
  const [progress, setProgress] = useState<{ played: Played | null; step: number }>({ played: null, step: 0 });
  const calls = played?.toolCalls ?? [];
  const instant = reduced || stepMs === 0;
  // Steps 0..n-1 act out the tool calls; step n is the outcome.
  const step = !played || instant ? calls.length : progress.played === played ? progress.step : 0;
  const done = step >= calls.length;

  useEffect(() => {
    if (!played || done || playing) return;
    const timer = setTimeout(() => setProgress({ played, step: step + 1 }), stepMs ?? stepFor(calls.length));
    return () => clearTimeout(timer);
  }, [played, step, done, playing, stepMs, calls.length]);

  let beat: Beat;
  if (playing) beat = THINKING;
  else if (!played) beat = IDLE;
  else if (!done) beat = beatFor(calls[step] as ToolCall);
  else beat = { pose: played.outcome, line: "", reviewing: false };

  return { beat, acting: !playing && !!played && !done, revealed: played && done && !playing ? played : null };
}
