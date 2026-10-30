# ADR-0014: Tiles are buttons; dragging is an optional shortcut (@dnd-kit/react)

**Status:** Accepted (2026-10-29)

## Context
The game is played by placing four tiles into four slots. It must work by mouse, touch and
keyboard and meet WCAG 2.2 AA, which includes 2.5.7 (Dragging Movements: anything done by
dragging must also be possible with a single pointer action) and 2.4.11 (Focus Not Obscured).

Each tile belongs to exactly one slot (personas only go in "Who you claim to be", and so on).
So "where does this tile go?" never needs asking.

Library options for drag-and-drop:
1. `@dnd-kit/core` 6.3: stable, widely used, but no release since December 2024.
2. `@dnd-kit/react` 0.5: the maintained successor, pre-1.0 (API may still change).
3. No library (native HTML5 drag-and-drop): no touch support, poor accessibility.

## Decision
- **Every tile is a toggle button** (`aria-pressed`). Tap, click, Enter or Space puts it in
  its slot, or takes it out if it is already there. Each slot has a labelled remove button.
  This is the primary interaction and works the same for touch, keyboard and screen readers.
- **Dragging is additive**, mouse and touch only: `@dnd-kit/react` 0.5 with the pointer
  sensor. Slots accept only their own tile type. The keyboard sensor is left out, because the
  buttons already cover the keyboard, and its Space/Enter handling would compete with them.
  dnd-kit's built-in screen-reader plugin is removed for the same reason (its instructions
  describe the keyboard sensor); the game announces placements in its own live region.
- On large screens the con bar is sticky, so slots stay in reach while dragging from the trays;
  `scroll-padding` keeps focused tiles clear of the sticky con bar and call bar.
- `@dnd-kit/react` and `@dnd-kit/dom` are pinned to 0.5.x (pnpm lockfile, 7-day cooldown).

## Consequences
- If a dnd-kit upgrade breaks, only dragging breaks; the game stays fully playable.
- Screen-reader users hear "Branch Manager, toggle button, not pressed", then a polite
  announcement such as "Branch Manager placed in Who you claim to be. 1 of 4 slots filled."
- Playwright covers all three paths: mouse drag (desktop), keyboard only, and taps on an
  emulated phone, each winning Level 1 under the two-minute target; axe runs on every screen.
