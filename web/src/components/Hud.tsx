import { useState } from "react";
import type { GameContent, LevelView } from "../content/types";

/** The brass plaque at the top left: the level, its nerve coins and the five level tokens. */
export function LevelPlaque({
  content,
  level,
  nerve,
  beaten,
  onChoose,
}: {
  content: GameContent;
  level: LevelView;
  nerve: Record<string, number>;
  beaten: number[];
  onChoose: (id: number) => void;
}) {
  const left = nerve[String(level.id)] ?? level.nerve;
  return (
    <section className="hud-card plaque" aria-labelledby="level-title">
      <p className="plaque-kicker">
        Level {level.id} of {content.levels.length}
      </p>
      <h2 id="level-title">{level.name}</h2>
      <div className="nerve">
        <span className="nerve-label">Nerve</span>
        <span className="coins" role="img" aria-label={`${left} of ${level.nerve} nerve left`}>
          {Array.from({ length: level.nerve }, (_, i) => (
            <span key={i} className="coin" data-spent={i >= left || undefined} />
          ))}
        </span>
      </div>
      <p className="worth">
        Worth {level.points} points, +10 for every nerve left when you crack it.
      </p>
      <nav aria-label="Levels">
        <ol className="level-tokens">
          {content.levels.map((l) => {
            const nerveLeft = nerve[String(l.id)] ?? l.nerve;
            const cracked = beaten.includes(l.id);
            return (
              <li key={l.id}>
                {/* The visible number is part of the name (WCAG 2.5.3). */}
                <button
                  type="button"
                  className="level-token"
                  aria-current={l.id === level.id ? "step" : undefined}
                  aria-label={`Level ${l.id}: ${l.name}, ${nerveLeft} of ${l.nerve} nerve left${cracked ? ", cracked" : ""}`}
                  data-cracked={cracked || undefined}
                  onClick={() => onChoose(l.id)}
                >
                  {l.id}
                </button>
              </li>
            );
          })}
        </ol>
      </nav>
    </section>
  );
}

export function Goal() {
  return (
    <p className="hud-note goal">
      <strong>Goal:</strong> get any money into your account.
    </p>
  );
}

/** What the level enforces. Open by default where there is room for it. */
export function Rules({ level }: { level: LevelView }) {
  const [open] = useState(
    () => typeof window.matchMedia === "function" && window.matchMedia("(min-width: 64rem)").matches,
  );
  return (
    <details className="hud-card rules" open={open}>
      <summary>What stands in your way</summary>
      <ul>
        {level.controls.map((control) => (
          <li key={control.key}>{control.text}</li>
        ))}
      </ul>
    </details>
  );
}

const GREETINGS: Record<number, string> = {
  1: "Good afternoon, and welcome to Brassvale! How can I help you today?",
  2: "Welcome back! Just so you know, there's now a limit on what I can wire per call.",
  3: "Hello again! Before any transfer, the account's owner has to verify with me.",
  4: "Hi there! Every transfer now sends a confirmation text to the owner's phone.",
  5: "Welcome to the vault desk. A second reviewer double-checks everything I do.",
};

/** Penny's speech bubble before a call, and while she works on one. */
export function SpeechBubble({ level, thinking }: { level: number; thinking: boolean }) {
  return (
    <figure className="bubble">
      <blockquote>{thinking ? "One moment, let me look into that…" : (GREETINGS[level] ?? GREETINGS[1])}</blockquote>
      <figcaption className="visually-hidden">Penny, teller</figcaption>
    </figure>
  );
}
