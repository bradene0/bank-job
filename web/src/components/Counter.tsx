import { Accessibility, PointerActivationConstraints, PointerSensor } from "@dnd-kit/dom";
import { DragDropProvider, useDraggable, useDroppable } from "@dnd-kit/react";
import { useState } from "react";
import { type GameContent, type SlotKey, type SlotView, type TileView } from "../content/types";
import { filledCount, tileById, type Slots } from "../game/state";

/** Each kind of tile is a different prop on the counter. */
const PROPS: Record<SlotKey, { kind: string; tray: string; empty: string }> = {
  persona: { kind: "badge", tray: "ID badges", empty: "Pin on a badge" },
  pretext: { kind: "paper", tray: "Papers", empty: "Slide over a story" },
  technique: { kind: "card", tray: "Moves", empty: "Play a move" },
  payload: { kind: "slip", tray: "Withdrawal slips", empty: "Fill in a slip" },
};

/**
 * A light pickup: a mouse lifts a tile after 4 px of movement, a finger after a short hold (so a
 * swipe across the trays still scrolls the page). A press without movement stays a click.
 */
const sensors = [
  PointerSensor.configure({
    activationConstraints: (event) =>
      event.pointerType === "touch"
        ? [new PointerActivationConstraints.Delay({ value: 150, tolerance: 8 })]
        : [new PointerActivationConstraints.Distance({ value: 4 })],
  }),
];

interface CounterProps {
  content: GameContent;
  slots: Slots;
  /** A call is in progress: nothing on the counter can change. */
  busy: boolean;
  live: boolean;
  onPlace: (tileId: string) => void;
  onClear: (slot: SlotKey) => void;
  onCall: () => void;
  announce: (message: string) => void;
}

/**
 * The counter in front of Penny's window: four spots for the con, the trays of props, and the
 * bell that makes the call. Dragging a prop onto its spot is the main way to play, but every tile
 * is also a toggle button (tap, click, Enter or Space puts it on its spot), so nothing requires a
 * drag (WCAG 2.5.7, ADR-0014).
 */
export function Counter({ content, slots, busy, live, onPlace, onClear, onCall, announce }: CounterProps) {
  const filled = filledCount(slots);
  const ready = filled === 4;
  const [lifted, setLifted] = useState<SlotKey | null>(null);

  const placed = (tile: TileView) => {
    onPlace(tile.id);
    const slot = content.slots.find((s) => s.key === tile.slot);
    const after = filledCount({ ...slots, [tile.slot]: tile.id });
    announce(`${tile.label} placed in ${slot?.label ?? tile.slot}. ${after} of 4 spots filled.`);
  };

  const removed = (key: SlotKey) => {
    const id = slots[key];
    const tile = id ? tileById(content, id) : undefined;
    const slot = content.slots.find((s) => s.key === key);
    onClear(key);
    announce(`${tile?.label ?? "Tile"} removed from ${slot?.label ?? key}. ${filled - 1} of 4 spots filled.`);
  };

  return (
    <DragDropProvider
      // Pointer only: keyboard users press the tile itself. dnd-kit's own screen-reader
      // instructions describe its keyboard sensor, so the game announces placements instead.
      sensors={sensors}
      plugins={(defaults) => defaults.filter((plugin) => plugin !== Accessibility)}
      onDragStart={(event) => {
        const tile = tileById(content, String(event.operation.source?.id ?? ""));
        setLifted(tile?.slot ?? null);
      }}
      onDragEnd={(event) => {
        setLifted(null);
        if (event.canceled || busy) return;
        const tile = tileById(content, String(event.operation.source?.id ?? ""));
        const target = event.operation.target?.id;
        if (tile && target === `slot-${tile.slot}`) placed(tile);
      }}
    >
      <section className="counter" aria-labelledby="counter-title">
        <div className="counter-inner">
          <h2 id="counter-title" className="visually-hidden">
            Your con
          </h2>
          <div className="spots-area">
            <ol className="spots">
              {content.slots.map((slot) => (
                <Spot
                  key={slot.key}
                  slot={slot}
                  tile={slots[slot.key] ? tileById(content, slots[slot.key] ?? "") : undefined}
                  awaiting={lifted === slot.key}
                  busy={busy}
                  onClear={() => removed(slot.key)}
                />
              ))}
            </ol>
            <p className="spot-count">
              <span>{filled} of 4 spots filled</span> · <span>Drag a prop onto its spot, or tap it.</span>
            </p>
          </div>

          <div className="trays">
            {content.slots.map((slot) => (
              <Tray
                key={slot.key}
                slot={slot}
                selected={slots[slot.key]}
                busy={busy}
                onToggle={(tile) => (slots[slot.key] === tile.id ? removed(slot.key) : placed(tile))}
              />
            ))}
          </div>

          <div className="bell-area">
            <Bell ringing={busy} />
            <button
              type="button"
              className="primary bell-button"
              disabled={!ready || busy}
              aria-describedby="bell-hint"
              onClick={onCall}
            >
              Ring the bell
            </button>
            <p id="bell-hint" className="bell-hint">
              {busy
                ? "Penny is on it…"
                : ready
                  ? "Your con is ready."
                  : `Fill all four spots first (${filled} of 4).`}
              {!live && !busy && " Bank closed: a recorded result."}
            </p>
          </div>
        </div>
      </section>
    </DragDropProvider>
  );
}

function Spot({
  slot,
  tile,
  awaiting,
  busy,
  onClear,
}: {
  slot: SlotView;
  tile: TileView | undefined;
  awaiting: boolean;
  busy: boolean;
  onClear: () => void;
}) {
  const { ref, isDropTarget } = useDroppable({ id: `slot-${slot.key}`, accept: slot.key });
  const prop = PROPS[slot.key];
  return (
    <li
      ref={ref}
      className="spot"
      data-slot={slot.key}
      data-filled={tile ? "true" : "false"}
      data-awaiting={awaiting || undefined}
      data-drop-target={isDropTarget || undefined}
    >
      <span className="spot-label">{slot.label}</span>
      <div className="spot-place">
        {tile ? (
          <div className="placed" data-kind={prop.kind}>
            <span className="placed-text">
              <span className="placed-label">{tile.label}</span>
              {tile.blurb && <span className="placed-blurb">{tile.blurb}</span>}
            </span>
            <button
              type="button"
              className="spot-clear"
              onClick={onClear}
              disabled={busy}
              aria-label={`Remove ${tile.label} from ${slot.label}`}
            >
              <span aria-hidden="true">×</span>
            </button>
          </div>
        ) : (
          <span className="spot-empty">{awaiting ? "Drop it here" : prop.empty}</span>
        )}
      </div>
    </li>
  );
}

function Tray({
  slot,
  selected,
  busy,
  onToggle,
}: {
  slot: SlotView;
  selected: string | null;
  busy: boolean;
  onToggle: (tile: TileView) => void;
}) {
  const headingId = `tray-${slot.key}`;
  const prop = PROPS[slot.key];
  return (
    <div className="tray" role="group" aria-labelledby={headingId} data-slot={slot.key}>
      <h3 id={headingId}>
        {prop.tray} <span className="tray-for">({slot.label.toLowerCase()})</span>
      </h3>
      <ul className="tiles" data-kind={prop.kind}>
        {slot.tiles.map((tile) => (
          <li key={tile.id}>
            <Tile
              tile={tile}
              kind={prop.kind}
              pressed={selected === tile.id}
              busy={busy}
              onToggle={() => onToggle(tile)}
            />
            {/* The blurb is the tile's description, not part of its name. */}
            {tile.blurb && (
              <span id={`blurb-${tile.id}`} className="visually-hidden">
                {tile.blurb}
              </span>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

function Tile({
  tile,
  kind,
  pressed,
  busy,
  onToggle,
}: {
  tile: TileView;
  kind: string;
  pressed: boolean;
  busy: boolean;
  onToggle: () => void;
}) {
  const { ref, isDragging } = useDraggable({ id: tile.id, type: tile.slot, disabled: busy });
  return (
    <button
      ref={ref}
      type="button"
      className="tile"
      data-kind={kind}
      data-slot={tile.slot}
      data-tile={tile.id}
      data-dragging={isDragging || undefined}
      aria-pressed={pressed}
      aria-describedby={tile.blurb ? `blurb-${tile.id}` : undefined}
      disabled={busy}
      onClick={onToggle}
    >
      {tile.label}
    </button>
  );
}

/** The brass service bell. It swings while Penny works on the call. */
function Bell({ ringing }: { ringing: boolean }) {
  return (
    <svg className="bell" viewBox="0 0 92 70" aria-hidden="true" data-ringing={ringing || undefined}>
      <ellipse cx="46" cy="62" rx="42" ry="7" fill="#2a170c" />
      <g className="bell-dome">
        <path d="M12 58 Q12 18 46 16 Q80 18 80 58 Z" fill="url(#bj-brass)" />
        <path d="M24 50 Q24 28 40 24" fill="none" stroke="#f3d98c" strokeWidth="3" strokeLinecap="round" opacity="0.7" />
        <circle cx="46" cy="12" r="6" fill="url(#bj-brass)" />
      </g>
      <rect x="8" y="56" width="76" height="8" rx="4" fill="#6b5a2e" />
    </svg>
  );
}
