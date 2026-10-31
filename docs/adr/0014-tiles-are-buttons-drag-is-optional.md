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

## Amendment (2026-10-30): drag first, on the teller scene
The game became a scene at Penny's window, where tiles are props on a counter. Dragging a prop to
its spot is now the interaction the game presents first; the decision above is unchanged
underneath: every tile is still a toggle button, and nothing requires a drag.

- **Lighter pickup.** dnd-kit's default activation (a 200 ms hold or 5 px, aborted by a fast
  move) felt sticky. A mouse now picks a prop up after 4 px; a finger after a 150 ms hold with
  8 px tolerance, so a swipe across the trays still scrolls the page. A press without movement
  stays a click, so tapping still places a prop.
- **Feedback.** While a prop is lifted, the spot that accepts it lights up ("Drop it here"). The
  lift uses `rotate` and `box-shadow`, because dnd-kit owns `transform`, `translate` and `scale`
  on the dragged element.
- **CSP.** `@dnd-kit/dom` injects three `<style>` blocks while dragging (the dragged element's
  position, the grab cursor, no text selection). The production policy (`style-src 'self'`)
  blocked them, which broke the drag feedback in production without failing any test, because the
  compose stack served no CSP. The policy now allows exactly those three blocks by hash, the
  compose nginx serves the same policy, and every end-to-end test fails on a CSP violation. A
  dnd-kit upgrade that changes the blocks fails CI until the hashes are updated.
