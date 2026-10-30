import { Accessibility, PointerSensor } from "@dnd-kit/dom";
import { DragDropProvider, useDraggable, useDroppable } from "@dnd-kit/react";
import { type GameContent, type SlotKey, type SlotView, type TileView } from "../content/types";
import { filledCount, tileById, type Slots } from "../game/state";

interface BoardProps {
  content: GameContent;
  slots: Slots;
  disabled: boolean;
  onPlace: (tileId: string) => void;
  onClear: (slot: SlotKey) => void;
  announce: (message: string) => void;
}

/**
 * The con bar (four slots) and the tile trays. Every tile belongs to exactly one slot, so a tile
 * is a toggle button: tap, click, Enter or Space puts it in its slot (or takes it out). Dragging
 * with a mouse or finger is the same action, offered on top (WCAG 2.5.7: nothing requires a drag).
 */
export function Board({ content, slots, disabled, onPlace, onClear, announce }: BoardProps) {
  const filled = filledCount(slots);

  const placed = (tile: TileView) => {
    onPlace(tile.id);
    const slot = content.slots.find((s) => s.key === tile.slot);
    const after = filledCount({ ...slots, [tile.slot]: tile.id });
    announce(`${tile.label} placed in ${slot?.label ?? tile.slot}. ${after} of 4 slots filled.`);
  };

  const removed = (key: SlotKey) => {
    const id = slots[key];
    const tile = id ? tileById(content, id) : undefined;
    const slot = content.slots.find((s) => s.key === key);
    onClear(key);
    announce(`${tile?.label ?? "Tile"} removed from ${slot?.label ?? key}. ${filled - 1} of 4 slots filled.`);
  };

  return (
    <DragDropProvider
      // Pointer only: keyboard users press the tile itself. dnd-kit's own screen-reader
      // instructions describe its keyboard sensor, so the game announces placements instead.
      sensors={[PointerSensor]}
      plugins={(defaults) => defaults.filter((plugin) => plugin !== Accessibility)}
      onDragEnd={(event) => {
        if (event.canceled || disabled) return;
        const tile = tileById(content, String(event.operation.source?.id ?? ""));
        const target = event.operation.target?.id;
        if (tile && target === `slot-${tile.slot}`) placed(tile);
      }}
    >
      <section className="con-bar" aria-labelledby="con-bar-title">
        <h2 id="con-bar-title">Your con</h2>
        <ol className="slots">
          {content.slots.map((slot) => (
            <Slot
              key={slot.key}
              slot={slot}
              tile={slots[slot.key] ? tileById(content, slots[slot.key] ?? "") : undefined}
              disabled={disabled}
              onClear={() => removed(slot.key)}
            />
          ))}
        </ol>
        <p className="slot-count" aria-live="off">
          {filled} of 4 slots filled
        </p>
      </section>

      <section className="trays" aria-labelledby="trays-title">
        <h2 id="trays-title">Pick your tiles</h2>
        <p className="hint">Tap a tile to put it in its slot, or drag it there.</p>
        {content.slots.map((slot) => (
          <Tray
            key={slot.key}
            slot={slot}
            selected={slots[slot.key]}
            disabled={disabled}
            onToggle={(tile) => (slots[slot.key] === tile.id ? removed(slot.key) : placed(tile))}
          />
        ))}
      </section>
    </DragDropProvider>
  );
}

function Slot({
  slot,
  tile,
  disabled,
  onClear,
}: {
  slot: SlotView;
  tile: TileView | undefined;
  disabled: boolean;
  onClear: () => void;
}) {
  const { ref, isDropTarget } = useDroppable({ id: `slot-${slot.key}`, accept: slot.key });
  return (
    <li
      ref={ref}
      className="slot"
      data-slot={slot.key}
      data-filled={tile ? "true" : "false"}
      data-drop-target={isDropTarget ? "true" : undefined}
    >
      <span className="slot-label">{slot.label}</span>
      {tile ? (
        <span className="slot-tile">
          <span className="slot-tile-label">{tile.label}</span>
          <button
            type="button"
            className="slot-clear"
            onClick={onClear}
            disabled={disabled}
            aria-label={`Remove ${tile.label} from ${slot.label}`}
          >
            <span aria-hidden="true">×</span>
          </button>
        </span>
      ) : (
        <span className="slot-empty">Empty</span>
      )}
    </li>
  );
}

function Tray({
  slot,
  selected,
  disabled,
  onToggle,
}: {
  slot: SlotView;
  selected: string | null;
  disabled: boolean;
  onToggle: (tile: TileView) => void;
}) {
  const headingId = `tray-${slot.key}`;
  return (
    <div className="tray" role="group" aria-labelledby={headingId} data-slot={slot.key}>
      <h3 id={headingId}>{slot.label}</h3>
      <ul className="tiles">
        {slot.tiles.map((tile) => (
          <li key={tile.id}>
            <Tile
              tile={tile}
              pressed={selected === tile.id}
              disabled={disabled}
              onToggle={() => onToggle(tile)}
            />
          </li>
        ))}
      </ul>
    </div>
  );
}

function Tile({
  tile,
  pressed,
  disabled,
  onToggle,
}: {
  tile: TileView;
  pressed: boolean;
  disabled: boolean;
  onToggle: () => void;
}) {
  const { ref, isDragging } = useDraggable({ id: tile.id, type: tile.slot, disabled });
  return (
    <button
      ref={ref}
      type="button"
      className="tile"
      data-slot={tile.slot}
      data-tile={tile.id}
      data-dragging={isDragging ? "true" : undefined}
      aria-pressed={pressed}
      disabled={disabled}
      onClick={onToggle}
    >
      <span className="tile-label">{tile.label}</span>
      {tile.blurb && <span className="tile-blurb">{tile.blurb}</span>}
    </button>
  );
}
