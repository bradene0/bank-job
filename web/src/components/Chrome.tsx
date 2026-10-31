import { useEffect, useState } from "react";
import type { ApiClient } from "../api/client";
import type { LeaderboardEntry } from "../api/types";
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

/** Top crews from the Kafka-fed projection, fetched each time the sheet opens. */
export function Leaderboard({ api, live }: { api: ApiClient; live: boolean }) {
  const [entries, setEntries] = useState<LeaderboardEntry[] | null>(null);
  useEffect(() => {
    if (!live) return;
    let cancelled = false;
    api
      .leaderboard()
      .then((e) => !cancelled && setEntries(e))
      .catch(() => !cancelled && setEntries(null));
    return () => {
      cancelled = true;
    };
  }, [api, live]);

  return (
    <div className="leaderboard">
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
    </div>
  );
}
