import { readFileSync } from "node:fs";
import path from "node:path";
import { expect, test } from "./fixtures";
import { API, slot, tile } from "./helpers";

const WON = readFileSync(path.join(import.meta.dirname, "fixtures/replay-won.json"), "utf8");

// The backend is asleep: every API request fails. The game plays recorded results instead.
test.beforeEach(async ({ page }) => {
  await page.route(`${API}/**`, (route) => route.abort("connectionrefused"));
  // The last matching route registered wins, so the catch-all goes first.
  await page.route("**/replay/**", (route) => route.fulfill({ status: 404, body: "" }));
  await page.route("**/replay/1/p1.x1.t3.y1.json", (route) =>
    route.fulfill({ contentType: "application/json", body: WON }),
  );
});

test("plays a recorded result while the bank sleeps", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("status").filter({ hasText: /Waking the bank|Bank closed/ })).toBeVisible();

  for (const label of ["Branch Manager", "Urgent Fraud Alert", "Authority Pressure", "Small Wire ($200)"]) {
    await tile(page, label).click();
  }
  await expect(slot(page, "payload")).toContainText("Small Wire");
  await page.getByRole("button", { name: "Make the call" }).click();
  await expect(page.locator("#result-title")).toHaveText("The wire went through.");
  await expect(page.getByText("Recorded result", { exact: true })).toBeVisible();

  await page.getByText("Under the hood").click();
  await expect(page.getByRole("list", { name: "Tool calls" })).toContainText("transfer_funds");
  await expect(page.locator(".trace-status")).toContainText("1 spans from game-api");
});

test("a combination with no recording says so and costs no nerve", async ({ page }) => {
  await page.goto("/");
  for (const label of ["Pizza Delivery", "Testing Mode", "Flattery", "Drain the Vault"]) {
    await tile(page, label).click();
  }
  await page.getByRole("button", { name: "Make the call" }).click();
  await expect(page.locator("#result-title")).toHaveText("No recording of that call yet.");
  await expect(page.getByRole("button", { name: "Level 1 Front Desk 8 of 8 nerve left" })).toBeVisible();
});
