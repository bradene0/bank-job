import { useCallback, useMemo, useRef, useState } from "react";
import defaultContent from "virtual:bankjob-content";
import { ApiClient } from "./api/client";
import { Leaderboard, StatusBadge, ThemeToggle } from "./components/Chrome";
import { Counter } from "./components/Counter";
import { Goal, LevelPlaque, Rules, SpeechBubble } from "./components/Hud";
import { Result } from "./components/Result";
import { ArtDefs } from "./components/scene/ArtDefs";
import { TellerWindow } from "./components/scene/TellerWindow";
import { UnderTheHood } from "./components/UnderTheHood";
import type { GameContent } from "./content/types";
import { DEFAULT_TIMING, useBackendStatus, type BackendTiming } from "./game/useBackendStatus";
import { useGame } from "./game/useGame";
import { usePennyScript, useReducedMotion } from "./game/usePennyScript";

const API_URL = import.meta.env.VITE_API_URL ?? "http://localhost:8080";
// Set in production (the wake Lambda); without it the game just waits for the API.
const WAKE_URL = import.meta.env.VITE_WAKE_URL ?? "";

interface AppProps {
  content?: GameContent;
  api?: ApiClient;
  wake?: (() => Promise<void>) | null;
  timing?: BackendTiming;
  /** How long Penny acts out each tool call; 0 skips straight to the outcome. */
  stepMs?: number;
}

const defaultWake = WAKE_URL
  ? async () => {
      await fetch(WAKE_URL, { method: "GET" });
    }
  : null;

export function App({
  content = defaultContent,
  api: apiProp,
  wake = defaultWake,
  timing = DEFAULT_TIMING,
  stepMs,
}: AppProps) {
  const api = useMemo(() => apiProp ?? new ApiClient(API_URL), [apiProp]);
  const backend = useBackendStatus(api, wake, timing);
  const game = useGame(content, api, backend);
  const [announcement, setAnnouncement] = useState("");
  const announce = useCallback((message: string) => setAnnouncement(message), []);
  const level = content.levels.find((l) => l.id === game.levelId) ?? content.levels[0];
  if (!level) throw new Error("content has no levels");
  const script = usePennyScript(game.playing, game.played, stepMs);
  const busy = game.playing || script.acting;
  const reducedMotion = useReducedMotion();
  const windowRef = useRef<HTMLDivElement>(null);
  // The player is usually down at the trays when they ring: bring Penny into view for the show.
  const ring = () => {
    windowRef.current?.scrollIntoView?.({ block: "center", behavior: reducedMotion ? "auto" : "smooth" });
    void game.makeTheCall();
  };

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
            <div className="window" ref={windowRef}>
              <TellerWindow
                level={level.id}
                controls={level.controls}
                pose={script.beat.pose}
                tool={script.beat.tool}
                decision={script.beat.decision}
                reviewing={script.beat.reviewing}
              />
            </div>
            <div className="hud hud-right">
              {busy ? (
                <SpeechBubble level={level.id} line={script.beat.line} />
              ) : script.revealed || game.notice ? (
                <Result
                  content={content}
                  played={script.revealed}
                  notice={game.notice}
                  onNextLevel={game.chooseLevel}
                />
              ) : (
                <SpeechBubble level={level.id} />
              )}
              {/* Each step of Penny's performance, read out politely as it happens. */}
              <p className="visually-hidden" role="status">
                {busy ? `Penny: ${script.beat.line}` : ""}
              </p>
            </div>
          </div>
        </section>
        <Counter
          content={content}
          slots={game.slots}
          busy={busy}
          live={backend.status === "live"}
          onPlace={game.placeTile}
          onClear={game.clearSlot}
          onCall={ring}
          announce={announce}
        />
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
