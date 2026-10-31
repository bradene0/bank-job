import { useCallback, useMemo, useState } from "react";
import defaultContent from "virtual:bankjob-content";
import { ApiClient } from "./api/client";
import { Board } from "./components/Board";
import { Leaderboard, StatusBadge, ThemeToggle } from "./components/Chrome";
import { Goal, LevelPlaque, Rules, SpeechBubble } from "./components/Hud";
import { Result } from "./components/Result";
import { ArtDefs } from "./components/scene/ArtDefs";
import type { Pose } from "./components/scene/Penny";
import { TellerWindow } from "./components/scene/TellerWindow";
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
  const level = content.levels.find((l) => l.id === game.levelId) ?? content.levels[0];
  if (!level) throw new Error("content has no levels");
  const pose: Pose = game.playing ? "thinking" : (game.played?.outcome ?? "idle");

  return (
    <>
      <ArtDefs />
      <a className="skip-link" href="#play">
        Skip to the game
      </a>
      <header className="topbar">
        <div className="topbar-brand">
          <h1>Bank Job</h1>
          <p className="tagline">Brassvale Savings &amp; Trust</p>
        </div>
        <div className="topbar-tools">
          <StatusBadge status={backend.status} />
          <ThemeToggle />
        </div>
      </header>

      <main id="play" tabIndex={-1}>
        <section className="scene" aria-label="Penny's teller window">
          <div className="scene-inner">
            <div className="hud hud-left">
              <LevelPlaque
                content={content}
                level={level}
                nerve={game.nerve}
                beaten={game.beaten}
                onChoose={game.chooseLevel}
              />
              <Goal />
              <Rules level={level} />
            </div>
            <div className="window">
              <TellerWindow level={level.id} controls={level.controls} pose={pose} />
            </div>
            <div className="hud hud-right">
              {!game.playing && (game.played || game.notice) ? (
                <Result content={content} played={game.played} notice={game.notice} onNextLevel={game.chooseLevel} />
              ) : (
                <SpeechBubble level={level.id} thinking={game.playing} />
              )}
            </div>
          </div>
        </section>
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
