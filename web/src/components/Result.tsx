import { useEffect, useRef } from "react";
import type { GameContent } from "../content/types";
import { formatUsd, type Played, type Points } from "../game/state";
import type { Notice } from "../game/useGame";

const HEADLINES = {
  won: "The wire went through.",
  blocked: "Stopped by the bank's controls.",
  refused: "Penny said no.",
} as const;

interface ResultProps {
  content: GameContent;
  played: Played | null;
  notice: Notice | null;
  onNextLevel: (level: number) => void;
}

/** The outcome of the last call. Focus moves here after each attempt so it is read out. */
export function Result({ content, played, notice, onNextLevel }: ResultProps) {
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    if (played || notice) heading.current?.focus();
  }, [played, notice]);

  if (!played && !notice) return null;
  const level = played ? content.levels.find((l) => l.id === played.level) : undefined;
  const next = played?.outcome === "won" ? content.levels.find((l) => l.id === played.level + 1) : undefined;

  return (
    <section className="result" aria-labelledby="result-title" data-outcome={played?.outcome}>
      <h2 id="result-title" ref={heading} tabIndex={-1}>
        {played ? HEADLINES[played.outcome] : notice ? noticeTitle(notice) : null}
      </h2>
      {notice && <p className="notice">{noticeText(notice)}</p>}
      {played && (
        <>
          <p className="result-summary">
            {played.outcome === "won"
              ? `${formatUsd(played.amountCents)} reached your account.`
              : "No money moved."}{" "}
            {played.points && <span className="points">{pointsText(played.points)}</span>}
            {played.source === "replay" && <span className="badge">Recorded result</span>}
          </p>
          <figure className="reply">
            <blockquote>{played.reply}</blockquote>
            <figcaption>Penny, teller</figcaption>
          </figure>
          {level && <p className="lesson">Lesson: {level.lesson}</p>}
          {next && (
            <button type="button" className="secondary" onClick={() => onNextLevel(next.id)}>
              Next level: {next.name}
            </button>
          )}
        </>
      )}
    </section>
  );
}

function pointsText(points: Points): string {
  switch (points.kind) {
    case "cracked":
      return `Level ${points.level} cracked: +${points.levelPoints}, nerve bonus +${points.nerveBonus}.`;
    case "already-cracked":
      return "Already cracked: no new points.";
    case "none":
      return "No points this time.";
  }
}

function noticeTitle(notice: Notice): string {
  switch (notice.kind) {
    case "not-recorded":
      return "No recording of that call yet.";
    case "no-nerve":
      return "You're out of nerve for this level.";
    case "replaying":
      return "Playing a recorded result.";
  }
}

function noticeText(notice: Notice): string {
  switch (notice.kind) {
    case "not-recorded":
      return "The bank is closed right now, and this combination has not been recorded. Try another one, or wait for the bank to wake up. Your nerve was not spent.";
    case "no-nerve":
      return `Level ${notice.level} has no attempts left in this session. Try another level.`;
    case "replaying":
      switch (notice.reason) {
        case "over_budget":
          return "Penny is on her break (today's model budget is spent), so this is a recorded result.";
        case "rate_limited":
          return "Too many new sessions from your network this hour, so this is a recorded result.";
        default:
          return "Live play is unavailable right now, so this is a recorded result.";
      }
  }
}
