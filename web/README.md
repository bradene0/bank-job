# web (TypeScript, React, Vite, dnd-kit)

The game: con bar and tile trays, levels and nerve, results, "Under the hood" (tool calls
with policy decisions, and the attempt's trace), leaderboard, live/waking/replay modes.

- **Content** (tile labels, level rules) is bundled from `../content/*.yaml` at build time by
  `content-plugin.ts`, without the prompt fragments; `pnpm check:bundle` fails if one leaks.
- **Placing tiles:** every tile is a toggle button (tap, click, Enter, Space); dragging is an
  optional shortcut (ADR-0014). Dark theme by default, light theme toggle,
  `prefers-reduced-motion` respected, WCAG 2.2 AA.
- **API:** `VITE_API_URL` (default `http://localhost:8080`) and `VITE_WAKE_URL` (optional)
  are baked in at build time. The browser sends tile ids and a level, never text (ADR-0003),
  and starts each attempt's trace with a `traceparent` (ADR-0015).
- **Replay mode:** `/replay/{level}/{comboId}.json` (`docs/contracts/replay.schema.json`),
  written by the eval harness. While the API is unreachable the game plays these.

```
pnpm install --frozen-lockfile    # Node 26 (see .nvmrc), pnpm 10
pnpm dev                          # http://localhost:5173 (stop the compose web service first)
pnpm lint && pnpm typecheck && pnpm test && pnpm build && pnpm check:bundle

# End to end, against the running compose stack (web on 5173, game-api on 8080):
pnpm exec playwright install chromium
pnpm e2e
```

Every Playwright test is a new player, so a local run needs more than the default 30 guest
sessions per IP per hour: `SESSIONS_MAX_PER_IP_PER_HOUR=1000 docker compose up -d game-api`.
CI runs the tests in `mcr.microsoft.com/playwright:v1.64.1-noble`; keep that tag equal to the
`@playwright/test` version in `pnpm-lock.yaml`.
