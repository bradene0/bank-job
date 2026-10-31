import { useEffect, useRef, useState } from "react";
import type { GameContent } from "../content/types";
import { formatUsd, type PassbookLine } from "../game/state";

const SHOWN = 4;

/**
 * Counts from the last value shown up to `target` over about a second (ease-out), so a deposit
 * visibly lands. With reduced motion it jumps.
 */
export function useTicker(target: number, reduced: boolean): number {
  const [shown, setShown] = useState(target);
  const from = useRef(target);
  useEffect(() => {
    if (reduced || typeof requestAnimationFrame !== "function") {
      from.current = target;
      const frame = setTimeout(() => setShown(target), 0);
      return () => clearTimeout(frame);
    }
    const start = performance.now();
    const origin = from.current;
    let frame = requestAnimationFrame(function tick(now) {
      const t = Math.min(1, (now - start) / 1_000);
      const value = Math.round(origin + (target - origin) * (1 - (1 - t) ** 3));
      from.current = value;
      setShown(value);
      if (t < 1) frame = requestAnimationFrame(tick);
    });
    return () => cancelAnimationFrame(frame);
  }, [target, reduced]);
  return shown;
}

/**
 * The player's passbook on the HUD: "your account", its balance, one line per wire that reached
 * it, and the crew's score. Recorded results (bank closed) are shown but never scored.
 */
export function Passbook({
  content,
  lines,
  reducedMotion,
}: {
  content: GameContent;
  lines: PassbookLine[];
  reducedMotion: boolean;
}) {
  const balance = lines.reduce((sum, line) => sum + line.amountCents, 0);
  const score = lines.reduce((sum, line) => sum + line.points, 0);
  const shownBalance = useTicker(balance, reducedMotion);
  const recent = lines.slice(-SHOWN);
  const earlier = lines.length - recent.length;

  return (
    <section className="hud-card passbook" aria-labelledby="passbook-title">
      <h2 id="passbook-title" className="passbook-title">
        Passbook <span className="passbook-account">· your account</span>
      </h2>
      <div className="passbook-totals">
        <p className="passbook-balance">
          <span className="passbook-key">Balance</span>{" "}
          <span className="passbook-amount" aria-hidden="true">
            {formatUsd(shownBalance)}
          </span>
          <span className="visually-hidden">{formatUsd(balance)}</span>
        </p>
        <p className="passbook-score">
          <span className="passbook-key">Score</span> <strong>{score.toLocaleString("en-US")}</strong>
        </p>
      </div>
      {lines.length === 0 ? (
        <p className="passbook-empty">No deposits yet.</p>
      ) : (
        <table className="passbook-lines">
          <caption className="visually-hidden">Deposits to your account</caption>
          <thead className="visually-hidden">
            <tr>
              <th scope="col">Level</th>
              <th scope="col">Deposit</th>
              <th scope="col">Points</th>
            </tr>
          </thead>
          <tbody>
            {earlier > 0 && (
              <tr className="passbook-earlier">
                <td colSpan={3}>{earlier} earlier</td>
              </tr>
            )}
            {recent.map((line, i) => (
              <tr key={earlier + i}>
                <th scope="row">{content.levels.find((l) => l.id === line.level)?.name ?? `Level ${line.level}`}</th>
                <td>+{formatUsd(line.amountCents)}</td>
                <td>{line.recorded ? "recorded" : `+${line.points} pts`}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
