# web (TypeScript, React, Vite, dnd-kit)

The game is a first-person scene at Penny's teller window: the brass window with Penny (hand-built
SVG, `src/components/scene`) and one prop per level control, HUD plaques (level, nerve, goal,
controls), the passbook for "your account" (balance, one line per win, score), and a wooden
counter where the con is built from props and the bell makes the call. Penny acts out each tool
call of an attempt and its outcome (`src/game/usePennyScript.ts`); "Under the hood" (tool calls
with policy decisions, and the attempt's trace) and the leaderboard open as dialogs from the HUD.
Live/waking/replay modes as before.

- **Content** (tile labels, level rules) is bundled from `../content/*.yaml` at build time by
  `content-plugin.ts`, without the prompt fragments; `pnpm check:bundle` fails if one leaks.
- **Placing props:** dragging a prop onto its spot is the main interaction, with a light pickup
  (4 px with a mouse, a 150 ms hold with a finger); every tile is also a toggle button (tap,
  click, Enter, Space), so nothing requires a drag (ADR-0014). Dark (evening) theme by default,
  light (daylight) toggle, WCAG 2.2 AA. With `prefers-reduced-motion` Penny changes pose without
  moving and skips straight to the outcome.
- **CSP:** no inline scripts or styles; styles live in `src/styles/*.css`, fonts are
  self-hosted. The only inline styles allowed are dnd-kit's three drag-time `<style>` blocks,
  by hash. `nginx.conf` serves the same policy to the compose stack (kept in sync by
  `csp.test.ts`), and every Playwright test fails on a CSP violation.
- **API:** `VITE_API_URL` (default `http://localhost:8080`) and `VITE_WAKE_URL` (optional)
  are baked in at build time. The browser sends tile ids and a level, never text (ADR-0003),
  and starts each attempt's trace with a `traceparent` (ADR-0015).
- **Replay mode:** `/replay/{level}/{comboId}.json` (`docs/contracts/replay.schema.json`),
  written by the eval harness. While the API is unreachable the game plays these.
- **Production:** Workers static assets at `bankjob.bradenevans.dev` (`wrangler.jsonc`,
  headers and CSP in `public/_headers`). `scripts/deploy-worker.sh <replay dir> [summary.json]`
  builds against `https://bankjob-live.bradenevans.dev`, adds the eval matrix's replay files
  under `/replay/` and deploys. The release workflow runs it after the owner approves the
  `production` environment; the first deploy (which creates the Worker) was run by hand.

```
pnpm install --frozen-lockfile    # Node 26 (see .nvmrc), pnpm 10
pnpm dev                          # http://localhost:5173 (stop the compose web service first)
pnpm lint && pnpm typecheck && pnpm test && pnpm build && pnpm check:bundle

# End to end, against the running compose stack (web on 5173, game-api on 8080):
pnpm exec playwright install chromium webkit
pnpm e2e    # desktop, Pixel 7, WebKit iPhone and reduced-motion projects
```

Every Playwright test is a new player, so a local run needs more than the default 30 guest
sessions per IP per hour: `SESSIONS_MAX_PER_IP_PER_HOUR=1000 docker compose up -d game-api`.
CI runs the tests in `mcr.microsoft.com/playwright:v1.64.1-noble`; keep that tag equal to the
`@playwright/test` version in `pnpm-lock.yaml`.
