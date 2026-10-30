import { useEffect, useState } from "react";
import type { ApiClient } from "../api/client";
import type { LeaderboardEntry } from "../api/types";
import type { GameContent } from "../content/types";
import { formatUsd } from "../game/state";
import type { BackendStatus } from "../game/useBackendStatus";

const STATUS_TEXT: Record<BackendStatus, string> = {
  checking: "Checking whether the bank is open…",
  live: "Live: Penny is at her desk",
  waking: "Waking the bank: recorded results meanwhile",
  asleep: "Bank closed: playing recorded results",
};

export function StatusBadge({ status }: { status: BackendStatus }) {
  return (
    <p className="status" role="status" data-status={status}>
      <span className="status-dot" aria-hidden="true" />
      {STATUS_TEXT[status]}
    </p>
  );
}

type Theme = "dark" | "light";
const THEME_KEY = "bankjob.theme";

function storedTheme(): Theme {
  try {
    return localStorage.getItem(THEME_KEY) === "light" ? "light" : "dark";
  } catch {
    return "dark";
  }
}

/** Dark by default; the choice is a per-viewer convenience kept in localStorage. */
export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>(storedTheme);
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try {
      localStorage.setItem(THEME_KEY, theme);
    } catch {
      // not remembered; fine
    }
  }, [theme]);
  return (
    <button
      type="button"
      className="theme-toggle"
      aria-pressed={theme === "light"}
      onClick={() => setTheme(theme === "light" ? "dark" : "light")}
    >
      Light theme
    </button>
  );
}

export function LevelPicker({
  content,
  levelId,
  nerve,
  beaten,
  onChoose,
}: {
  content: GameContent;
  levelId: number;
  nerve: Record<string, number>;
  beaten: number[];
  onChoose: (id: number) => void;
}) {
  return (
    <nav className="levels" aria-label="Levels">
      <ol>
        {content.levels.map((level) => {
          const left = nerve[String(level.id)] ?? level.nerve;
          return (
            <li key={level.id}>
              {/* Plain text separated by spaces: the accessible name is the visible text (WCAG
                  2.5.3), computed the same way by every browser. */}
              <button
                type="button"
                aria-current={level.id === levelId ? "step" : undefined}
                onClick={() => onChoose(level.id)}
              >
                <span className="level-number">Level {level.id}</span>{" "}
                <span className="level-name">{level.name}</span>{" "}
                <span className="level-meta">
                  {left} of {level.nerve} nerve left
                  {beaten.includes(level.id) && (
                    <>
                      {" "}
                      <span className="beaten">· beaten</span>
                    </>
                  )}
                </span>
              </button>
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

export function Briefing({ content, levelId }: { content: GameContent; levelId: number }) {
  const level = content.levels.find((l) => l.id === levelId);
  if (!level) return null;
  return (
    <section className="briefing" aria-labelledby="briefing-title">
      <h2 id="briefing-title">
        Level {level.id}: {level.name}
      </h2>
      <p>
        <strong>Goal:</strong> get any money into your account.
      </p>
      <p>
        Crack a level for its points; +10 for every nerve you have left. This level is worth{" "}
        {level.points}.
      </p>
      <h3>What stands in your way</h3>
      <ul>
        {level.controls.map((control) => (
          <li key={control.key}>{control.text}</li>
        ))}
      </ul>
    </section>
  );
}

/** Top crews from the Kafka-fed projection. It updates a moment after each attempt. */
export function Leaderboard({ api, live, refreshKey }: { api: ApiClient; live: boolean; refreshKey: unknown }) {
  const [entries, setEntries] = useState<LeaderboardEntry[] | null>(null);
  useEffect(() => {
    if (!live) return;
    let cancelled = false;
    // The projection is fed asynchronously; give the consumer a moment after an attempt.
    const timer = setTimeout(() => {
      api
        .leaderboard()
        .then((e) => !cancelled && setEntries(e))
        .catch(() => !cancelled && setEntries(null));
    }, refreshKey ? 1_500 : 0);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [api, live, refreshKey]);

  return (
    <section className="leaderboard" aria-labelledby="leaderboard-title">
      <h2 id="leaderboard-title">Leaderboard</h2>
      {!live ? (
        <p>The leaderboard opens with the bank.</p>
      ) : entries === null ? (
        <p>Loading…</p>
      ) : entries.length === 0 ? (
        <p>No crew has pulled off a heist yet. Be the first.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th scope="col">Rank</th>
              <th scope="col">Crew</th>
              <th scope="col">Score</th>
              <th scope="col">Levels cracked</th>
              <th scope="col">Haul</th>
            </tr>
          </thead>
          <tbody>
            {entries.slice(0, 10).map((entry) => (
              <tr key={entry.alias}>
                <td>{entry.rank}</td>
                <td>{entry.alias}</td>
                <td>{entry.score.toLocaleString("en-US")}</td>
                <td>{entry.levelsCracked}</td>
                <td>{formatUsd(entry.stolenCents)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
