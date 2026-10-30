import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { tile, winLevel1 } from "./helpers";

// WCAG 2.2 AA, checked by axe on each screen, in both themes.
const TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"];

async function expectNoViolations(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(TAGS).analyze();
  expect(results.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target).join(", ")}`)).toEqual([]);
}

test.beforeEach(async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("Live: Penny is at her desk")).toBeVisible();
});

test("the game board has no violations in either theme", async ({ page }) => {
  await tile(page, "Bank Examiner").click();
  await expectNoViolations(page);
  await page.getByRole("button", { name: "Light theme" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await expectNoViolations(page);
});

test("a result and the under-the-hood panel have no violations", async ({ page }) => {
  await winLevel1(
    page,
    async (label) => tile(page, label).click(),
    () => page.getByRole("button", { name: "Make the call" }).click(),
  );
  await page.getByText("Under the hood").click();
  await expect(page.locator(".trace-status")).not.toContainText("Collecting");
  await page.getByText("Arguments and result").first().click();
  await expectNoViolations(page);
  await page.getByRole("button", { name: "Light theme" }).click();
  await expectNoViolations(page);
});
