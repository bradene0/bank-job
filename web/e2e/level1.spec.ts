import { expect, test } from "./fixtures";
import { bell, callAndWait, drag, onPhone, spot, tile, winLevel1 } from "./helpers";

// The M4 bar, on the teller scene: a first-time player finishes Level 1 in under two minutes, by
// mouse, touch and keyboard. These run against the live stack (stub teller).
const TWO_MINUTES = 120_000;

test.beforeEach(async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("Live: Penny is at her desk")).toBeVisible();
});

test("mouse: drag props onto the counter and win Level 1", async ({ page }, info) => {
  test.skip(info.project.name !== "desktop", "mouse drag is a desktop interaction");
  const started = Date.now();
  await winLevel1(
    page,
    async (label, key) => drag(page, tile(page, label), spot(page, key)),
    () => bell(page).click(),
  );
  expect(Date.now() - started).toBeLessThan(TWO_MINUTES);
  await expect(page.getByText("$200.00 reached your account.")).toBeVisible();
  await expect(page.getByRole("button", { name: /^Level 1: Front Desk, .*, cracked$/ })).toBeVisible();
});

test("keyboard: build the con and ring the bell without a pointer", async ({ page }, info) => {
  test.skip(onPhone(info), "keyboard play is covered on desktop");
  const started = Date.now();
  await winLevel1(
    page,
    async (label) => {
      await tile(page, label).focus();
      await page.keyboard.press("Enter");
    },
    async () => {
      await bell(page).focus();
      await page.keyboard.press("Enter");
    },
  );
  expect(Date.now() - started).toBeLessThan(TWO_MINUTES);
  // Focus lands on the result, so a screen reader reads it out.
  await expect(page.locator("#result-title")).toBeFocused();
});

test("touch: tap props into place and win Level 1 on a phone", async ({ page }, info) => {
  test.skip(!onPhone(info), "tap play is covered on the phones");
  const started = Date.now();
  await winLevel1(
    page,
    async (label) => tile(page, label).tap(),
    () => bell(page).tap(),
  );
  expect(Date.now() - started).toBeLessThan(TWO_MINUTES);
});

test("Penny acts out the call, then the passbook takes the deposit", async ({ page }, info) => {
  test.skip(info.project.name !== "desktop", "the animated performance is checked on desktop");
  for (const label of ["Urgent Fraud Alert", "Authority Pressure", "Small Wire ($200)", "Bank Examiner"]) {
    await tile(page, label).click();
  }
  const answered = page.waitForResponse((r) => r.url().includes("/v1/attempts"));
  await bell(page).click();
  // Ringing brings Penny's window into view, and she thinks while the call runs.
  await expect(page.locator(".window")).toBeInViewport();
  const { toolCalls } = (await (await answered).json()) as { toolCalls: unknown[] };
  const penny = page.locator(".penny");
  const outcome = page.locator("#result-title");
  // Each tool call gets its pose before the outcome is revealed.
  if (toolCalls.length > 0) {
    await expect(penny).toHaveAttribute("data-pose", /lookup|balance|verify|document|phone|transfer|flag/);
    await expect(outcome).toBeHidden();
  }
  await expect(outcome).toBeVisible();
  await expect(penny).toHaveAttribute("data-pose", /won|blocked|refused/);
  if ((await outcome.textContent()) === "The wire went through.") {
    const passbook = page.getByRole("region", { name: /^Passbook/ });
    await expect(passbook.getByRole("rowheader", { name: "Front Desk" })).toBeVisible();
    await expect(passbook.locator(".passbook-amount")).toHaveText("$200.00");
  }
});

test("under the hood shows the policy decisions and the attempt's trace", async ({ page }, info) => {
  // A combination nobody has played today, so Penny really runs (a cached one replays her
  // recorded decisions without the agent, ADR-0012).
  const pick = <T,>(xs: T[]) => xs[Math.floor(Math.random() * xs.length)] as T;
  const con = [
    pick(["Long-Lost Account Holder", "Penny's Developer", "Pizza Delivery"]),
    pick(["System Migration", "Surprise Birthday Gift", "Regulatory Deadline", "Prize Payout", "Testing Mode"]),
    pick(["Small Steps", "Hidden Note", "Let's Play a Game", "Flattery"]),
    pick(["Medium Wire ($5,000)", "Big Wire ($50,000)", "Drain the Vault"]),
  ];
  for (const label of con) await tile(page, label).click();
  await callAndWait(page, () => bell(page).click());

  await page.getByRole("button", { name: "Under the hood" }).click();
  const hood = page.getByRole("dialog", { name: "Under the hood" });
  await expect(hood.getByRole("list", { name: "Tool calls" }).locator("li").first()).toBeVisible();
  const status = hood.locator(".trace-status");
  await expect(status).not.toContainText("Collecting");
  if (await hood.getByText(/Someone played this exact con earlier today/).isVisible()) {
    await expect(status).toContainText("spans from game-api");
  } else {
    // One trace from the browser's request through Penny's MCP calls to the Kafka consumers.
    await expect(status).toContainText(/spans from (game-api and agent|agent and game-api)/);
    await expect(hood.getByRole("list", { name: "Spans" }).locator('[data-service="agent"]').first()).toBeVisible();
  }
  await page.keyboard.press("Escape");
  await expect(hood).toBeHidden();
  // The dialog hands focus back to its button. (WebKit never focuses a tapped button, so there
  // is nothing to hand back there.)
  if (info.project.name !== "webkit-phone") {
    await expect(page.getByRole("button", { name: "Under the hood" })).toBeFocused();
  }
});

test("with reduced motion Penny skips straight to the outcome, without moving", async ({ page }, info) => {
  test.skip(info.project.name !== "reduced-motion", "runs with prefers-reduced-motion: reduce");
  for (const label of ["Urgent Fraud Alert", "Authority Pressure", "Small Wire ($200)", "Bank Examiner"]) {
    await tile(page, label).click();
  }
  await expect(page.locator(".penny-body")).toHaveCSS("animation-name", "none");
  const answered = page.waitForResponse((r) => r.url().includes("/v1/attempts"));
  await bell(page).click();
  await answered;
  // No tool-call beats: the result is there as soon as the answer is.
  await expect(page.locator("#result-title")).toBeVisible({ timeout: 1_000 });
  await expect(page.locator(".penny")).toHaveAttribute("data-pose", /won|blocked|refused/);
  await expect(bell(page)).toBeEnabled();
});
