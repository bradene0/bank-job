import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiClient } from "./api/client";
import { App } from "./App";
import { fakeBackend, json, replayOf, type FakeOptions } from "./test/fakeBackend";

const FAST = { wakingPollMs: 50, wakeWindowMs: 10_000, asleepPollMs: 50 };

function renderGame(options: FakeOptions = {}, stepMs = 0) {
  const backend = fakeBackend(options);
  // Replay files are fetched from the page's own origin, like the static site serves them.
  vi.stubGlobal("fetch", backend.fetchImpl);
  const api = new ApiClient("http://api.test", backend.fetchImpl);
  render(<App api={api} wake={null} timing={FAST} stepMs={stepMs} />);
  return backend;
}

async function buildCon(user: ReturnType<typeof userEvent.setup>, labels: string[]) {
  for (const label of labels) {
    await user.click(screen.getByRole("button", { name: new RegExp(`^${label}`) }));
  }
}

const WINNING_CON = ["Branch Manager", "Urgent Fraud Alert", "Authority Pressure", "Small Wire"];

beforeEach(() => sessionStorage.clear());
afterEach(() => vi.unstubAllGlobals());

describe("App", () => {
  it("renders the game title as the page heading", () => {
    renderGame();
    expect(screen.getByRole("heading", { level: 1, name: "Bank Job" })).toBeInTheDocument();
  });

  it("greets the player at Penny's window and shows the level's controls", async () => {
    const user = userEvent.setup();
    renderGame();
    expect(screen.getByRole("img", { name: /Penny, Brassvale's AI teller/ })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Front Desk" })).toBeInTheDocument();
    expect(screen.getByText(/welcome to Brassvale/)).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "8 of 8 nerve left" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /^Level 3: Policy Engine/ }));
    expect(screen.getByRole("heading", { level: 2, name: "Policy Engine" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^Level 3: Policy Engine/ })).toHaveAttribute("aria-current", "step");
    expect(screen.getByText("The caller must verify as the account's owner")).toBeInTheDocument();
  });

  it("plays a live attempt: tiles in, tile ids out, the ledger's outcome back", async () => {
    const user = userEvent.setup();
    const backend = renderGame();
    await screen.findByText(/Live: Penny is at her desk/);

    const call = screen.getByRole("button", { name: "Ring the bell" });
    expect(call).toBeDisabled();
    await buildCon(user, WINNING_CON);
    expect(screen.getByText("4 of 4 spots filled")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^Branch Manager/ })).toHaveAttribute("aria-pressed", "true");
    await user.click(call);

    const result = await screen.findByRole("heading", { name: "The wire went through." });
    expect(result).toHaveFocus();
    expect(screen.getByText("$200.00 reached your account.")).toBeInTheDocument();
    // Penny's words are text, never markup.
    expect(screen.getByText(/<b>Have a lovely day!<\/b>/)).toBeInTheDocument();
    // The game calls the mule "your account"; only the raw data under the hood has its number.
    const resultRegion = screen.getByRole("region", { name: "The wire went through." });
    expect(within(resultRegion).getByText(/transferred \$200\.00 to your account/)).toBeInTheDocument();
    expect(resultRegion).not.toHaveTextContent("BV-MULE-01");
    // The passbook: one line for the wire, the level's points plus 7 nerve left x 10, and the score.
    const passbook = screen.getByRole("region", { name: /^Passbook/ });
    expect(within(passbook).getByRole("rowheader", { name: "Front Desk" })).toBeInTheDocument();
    expect(within(passbook).getByText("+170 pts")).toBeInTheDocument();
    expect(within(passbook).getByText("170")).toBeInTheDocument();
    expect(within(passbook).getAllByText("$200.00").length).toBeGreaterThan(0);
    const attempt = backend.requests.find((r) => r.url.endsWith("/v1/attempts"));
    expect(JSON.parse(attempt?.body ?? "null")).toEqual({
      level: 1,
      tiles: { persona: "p1", pretext: "x1", technique: "t3", payload: "y1" },
    });
    expect(screen.getByRole("button", { name: "Level 1: Front Desk, 7 of 8 nerve left, cracked" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Under the hood" }));
    const hood = screen.getByRole("dialog", { name: "Under the hood" });
    const calls = within(hood).getByRole("list", { name: "Tool calls" });
    expect(calls.querySelectorAll('li[data-decision="allow"]')).toHaveLength(2);
    expect(within(calls).getByText("transfer_funds")).toBeInTheDocument();
    await user.click(within(hood).getByRole("button", { name: "Close" }));
    expect(screen.queryByRole("dialog", { name: "Under the hood" })).not.toBeInTheDocument();
  });

  it("acts out each tool call before revealing the result", async () => {
    const user = userEvent.setup();
    renderGame({}, 40);
    await screen.findByText(/Live: Penny is at her desk/);
    await buildCon(user, WINNING_CON);
    await user.click(screen.getByRole("button", { name: "Ring the bell" }));

    // lookup_account, then transfer_funds, each in the bubble and read out as a status.
    expect(await screen.findByText("Let me look up that account…", { selector: "blockquote" })).toBeInTheDocument();
    expect(await screen.findByText("Keying in the wire…", { selector: "blockquote" })).toBeInTheDocument();
    expect(screen.getByText("Penny: Keying in the wire…")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Ring the bell" })).toBeDisabled();
    expect(screen.queryByRole("heading", { name: "The wire went through." })).not.toBeInTheDocument();

    expect(await screen.findByRole("heading", { name: "The wire went through." })).toHaveFocus();
  });

  it("places and removes tiles with the keyboard alone", async () => {
    const user = userEvent.setup();
    renderGame();
    const tile = screen.getByRole("button", { name: /^Bank Examiner/ });
    tile.focus();
    await user.keyboard("{Enter}");
    expect(tile).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByText("1 of 4 spots filled")).toBeInTheDocument();
    expect(await screen.findByText(/Bank Examiner placed in Who you claim to be/)).toBeInTheDocument();

    await user.keyboard(" ");
    expect(tile).toHaveAttribute("aria-pressed", "false");

    await user.keyboard("{Enter}");
    await user.click(screen.getByRole("button", { name: "Remove Bank Examiner from Who you claim to be" }));
    expect(screen.getByText("0 of 4 spots filled")).toBeInTheDocument();
  });

  it("falls back to a recorded result when the agent is down, without a dead end", async () => {
    const user = userEvent.setup();
    renderGame({
      attempt: () => json(503, { reason: "agent_unavailable", replay: true }),
      replays: { "p1.x1.t3.y1": replayOf("p1.x1.t3.y1", "refused") },
    });
    await screen.findByText(/Live: Penny is at her desk/);
    await buildCon(user, WINNING_CON);
    await user.click(screen.getByRole("button", { name: "Ring the bell" }));

    expect(await screen.findByRole("heading", { name: "Penny said no." })).toBeInTheDocument();
    expect(screen.getByText("Recorded result")).toBeInTheDocument();
    expect(screen.getByText(/Live play is unavailable right now/)).toBeInTheDocument();
  });

  it("plays recorded results while the bank sleeps, and spends no nerve on a missing one", async () => {
    const user = userEvent.setup();
    renderGame({ up: false, replays: { "p2.x1.t3.y1": replayOf("p2.x1.t3.y1", "won") } });
    await screen.findByText(/Waking the bank/);

    await buildCon(user, WINNING_CON);
    await user.click(screen.getByRole("button", { name: "Ring the bell" }));
    expect(await screen.findByRole("heading", { name: "No recording of that call yet." })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Level 1: Front Desk, 8 of 8 nerve left" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /^Bank Examiner/ }));
    await user.click(screen.getByRole("button", { name: "Ring the bell" }));
    expect(await screen.findByRole("heading", { name: "The wire went through." })).toBeInTheDocument();
    expect(screen.getByText("Recorded result")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Level 1: Front Desk, 7 of 8 nerve left, cracked" })).toBeInTheDocument();
    // A recorded win goes in the passbook, but never scores.
    const passbook = screen.getByRole("region", { name: /^Passbook/ });
    expect(within(passbook).getByText("recorded")).toBeInTheDocument();
    expect(within(passbook).getByText("0")).toBeInTheDocument();
  });

  it("opens the leaderboard from the HUD", async () => {
    const user = userEvent.setup();
    renderGame();
    await screen.findByText(/Live: Penny is at her desk/);
    await user.click(screen.getByRole("button", { name: "Leaderboard" }));
    const sheet = screen.getByRole("dialog", { name: "Leaderboard" });
    expect(await within(sheet).findByText(/No crew has pulled off a heist yet/)).toBeInTheDocument();
  });

  it("switches to live play once the bank wakes up", async () => {
    const backend = renderGame({ up: false });
    await screen.findByText(/Waking the bank/);
    backend.state.up = true;
    await waitFor(() => expect(screen.getByText(/Live: Penny is at her desk/)).toBeInTheDocument());
  });

  it("tells the player when a level is out of nerve", async () => {
    const user = userEvent.setup();
    renderGame({ attempt: () => json(429, { status: 429 }) });
    await screen.findByText(/Live: Penny is at her desk/);
    await buildCon(user, WINNING_CON);
    await user.click(screen.getByRole("button", { name: "Ring the bell" }));
    expect(await screen.findByRole("heading", { name: "You're out of nerve for this level." })).toBeInTheDocument();
  });
});
