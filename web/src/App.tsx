import { useCallback, useMemo, useState } from "react";
import defaultContent from "virtual:bankjob-content";
import { ApiClient } from "./api/client";
import { Board } from "./components/Board";
import { Briefing, Leaderboard, LevelPicker, StatusBadge, ThemeToggle } from "./components/Chrome";
import { Result } from "./components/Result";
import { UnderTheHood } from "./components/UnderTheHood";
import type { GameContent } from "./content/types";
import { filledCount } from "./game/state";
import { DEFAULT_TIMING, useBackendStatus, type BackendTiming } from "./game/useBackendStatus";
import { useGame } from "./game/useGame";

const API_URL = import.meta.env.VITE_API_URL ?? "http://localhost:8080";
// Set in production (the wake Lambda); without it the game just waits for the API.
const WAKE_URL = import.meta.env.VITE_WAKE_URL ?? "";

interface AppProps {
  content?: GameContent;
  api?: ApiClient;
  wake?: (() => Promise<void>) | null;
  timing?: BackendTiming;
}

const defaultWake = WAKE_URL
  ? async () => {
      await fetch(WAKE_URL, { method: "GET" });
    }
  : null;

export function App({ content = defaultContent, api: apiProp, wake = defaultWake, timing = DEFAULT_TIMING }: AppProps) {
  const api = useMemo(() => apiProp ?? new ApiClient(API_URL), [apiProp]);
  const backend = useBackendStatus(api, wake, timing);
  const game = useGame(content, api, backend);
  const [announcement, setAnnouncement] = useState("");
  const announce = useCallback((message: string) => setAnnouncement(message), []);
  const ready = filledCount(game.slots) === 4;

  return (
    <>
      <a className="skip-link" href="#play">
        Skip to the game
      </a>
      <header className="masthead">
        <div>
          <p className="brand">Brassvale Savings &amp; Trust</p>
          <h1>Bank Job</h1>
          <p className="tagline">Trusted since 1911. Automated since Tuesday.</p>
        </div>
        <div className="masthead-tools">
          <StatusBadge status={backend.status} />
          <ThemeToggle />
        </div>
      </header>

      <main id="play" tabIndex={-1}>
        <LevelPicker
          content={content}
          levelId={game.levelId}
          nerve={game.nerve}
          beaten={game.beaten}
          onChoose={game.chooseLevel}
        />
        <Briefing content={content} levelId={game.levelId} />
        <Board
          content={content}
          slots={game.slots}
          disabled={game.playing}
          onPlace={game.placeTile}
          onClear={game.clearSlot}
          announce={announce}
        />
        <div className="call">
          <button
            type="button"
            className="primary"
            disabled={!ready || game.playing}
            aria-describedby="call-hint"
            onClick={() => void game.makeTheCall()}
          >
            {game.playing ? "Penny is thinking…" : "Make the call"}
          </button>
          <p id="call-hint" className="hint">
            {ready ? "Your con is ready." : `Fill all four slots first (${filledCount(game.slots)} of 4).`}{" "}
            {backend.status === "live" ? "" : "The bank is closed, so you'll get a recorded result."}
          </p>
        </div>
        <Result content={content} played={game.played} notice={game.notice} onNextLevel={game.chooseLevel} />
        {game.played && (
          <UnderTheHood played={game.played} api={api} token={game.session?.token ?? null} />
        )}
        <Leaderboard api={api} live={backend.status === "live"} refreshKey={game.played} />
      </main>

      <footer>
        <p>All characters, banks and accounts are fictional. No real money moves.</p>
      </footer>
      <div className="visually-hidden" aria-live="polite" aria-atomic="true">
        {announcement}
      </div>
    </>
  );
}
