import AxeBuilder from "@axe-core/playwright";
import type { Page } from "@playwright/test";
import { expect, test } from "./fixtures";
import { bell, tile, winLevel1 } from "./helpers";

// WCAG 2.2 AA, checked by axe on each screen of the teller scene, in both themes.
const TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"];

async function expectNoViolations(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(TAGS).analyze();
  expect(results.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target).join(", ")}`)).toEqual([]);
}

async function lightTheme(page: Page) {
  await page.getByRole("button", { name: "Light theme" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
}

test.beforeEach(async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("Live: Penny is at her desk")).toBeVisible();
});

test("the scene and the counter have no violations in either theme", async ({ page }) => {
  await tile(page, "Bank Examiner").click();
  await page.getByRole("button", { name: /^Level 5: The Vault/ }).click();
  await expectNoViolations(page);
  await lightTheme(page);
  await expectNoViolations(page);
});

test("a result, the passbook and the under-the-hood sheet have no violations", async ({ page }) => {
  await winLevel1(
    page,
    async (label) => tile(page, label).click(),
    () => bell(page).click(),
  );
  await expectNoViolations(page);

  await page.getByRole("button", { name: "Under the hood" }).click();
  const hood = page.getByRole("dialog", { name: "Under the hood" });
  await expect(hood.locator(".trace-status")).not.toContainText("Collecting");
  await hood.getByText("Arguments and result").first().click();
  await expectNoViolations(page);
  await page.keyboard.press("Escape");

  await lightTheme(page);
  await page.getByRole("button", { name: "Under the hood" }).click();
  await expect(hood.locator(".trace-status")).not.toContainText("Collecting");
  await expectNoViolations(page);
});

test("the leaderboard sheet has no violations", async ({ page }) => {
  await page.getByRole("button", { name: "Leaderboard" }).click();
  const sheet = page.getByRole("dialog", { name: "Leaderboard" });
  await expect(sheet.getByText(/Loading/)).toBeHidden();
  await expectNoViolations(page);
});
