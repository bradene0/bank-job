import { expect, test } from "./fixtures";
import { callAndWait, drag, slot, tile, winLevel1 } from "./helpers";

// The M4 bar: a first-time player finishes Level 1 in under two minutes, by mouse, touch and
// keyboard. These run against the live stack (stub teller).
const TWO_MINUTES = 120_000;

test.beforeEach(async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("Live: Penny is at her desk")).toBeVisible();
});

test("mouse: drag tiles into the con bar and win Level 1", async ({ page }, info) => {
  test.skip(info.project.name !== "desktop", "mouse drag is a desktop interaction");
  const started = Date.now();
  await winLevel1(
    page,
    async (label, key) => drag(page, tile(page, label), slot(page, key)),
    () => page.getByRole("button", { name: "Make the call" }).click(),
  );
  expect(Date.now() - started).toBeLessThan(TWO_MINUTES);
  await expect(page.getByText("$200.00 reached your account.")).toBeVisible();
  await expect(page.getByRole("button", { name: /^Level 1 Front Desk .* · beaten$/ })).toBeVisible();
});

test("keyboard: build the con and make the call without a pointer", async ({ page }, info) => {
  test.skip(info.project.name !== "desktop", "keyboard play is covered on desktop");
  const started = Date.now();
  await winLevel1(
    page,
    async (label) => {
      await tile(page, label).focus();
      await page.keyboard.press("Enter");
    },
    async () => {
      await page.getByRole("button", { name: "Make the call" }).focus();
      await page.keyboard.press("Enter");
    },
  );
  expect(Date.now() - started).toBeLessThan(TWO_MINUTES);
  // Focus lands on the result, so a screen reader reads it out.
  await expect(page.locator("#result-title")).toBeFocused();
});

test("touch: tap tiles into place and win Level 1 on a phone", async ({ page }, info) => {
  test.skip(info.project.name !== "phone", "tap play is covered on the phone");
  const started = Date.now();
  await winLevel1(
    page,
    async (label) => tile(page, label).tap(),
    () => page.getByRole("button", { name: "Make the call" }).tap(),
  );
  expect(Date.now() - started).toBeLessThan(TWO_MINUTES);
});

test("under the hood shows the policy decisions and the attempt's trace", async ({ page }) => {
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
  await callAndWait(page, () => page.getByRole("button", { name: "Make the call" }).click());

  await page.getByText("Under the hood").click();
  await expect(page.getByRole("list", { name: "Tool calls" }).locator("li").first()).toBeVisible();
  await expect(page.locator(".trace-status")).not.toContainText("Collecting");
  const status = page.locator(".trace-status");
  if (await page.getByText(/Someone played this exact con earlier today/).isVisible()) {
    await expect(status).toContainText("spans from game-api");
  } else {
    // One trace from the browser's request through Penny's MCP calls to the Kafka consumers.
    await expect(status).toContainText(/spans from (game-api and agent|agent and game-api)/);
    await expect(page.getByRole("list", { name: "Spans" }).locator('[data-service="agent"]').first()).toBeVisible();
  }
});
