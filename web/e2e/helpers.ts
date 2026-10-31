import { expect, type Locator, type Page, type TestInfo } from "@playwright/test";

export const API = process.env.API_URL ?? "http://localhost:8080";

/** Projects that play on a touch screen (Chromium's Pixel 7, WebKit's iPhone). */
export const onPhone = (info: TestInfo) => info.project.name.includes("phone");

export const tile = (page: Page, label: string) => page.getByRole("button", { name: label, exact: true });

export const spot = (page: Page, key: string) => page.locator(`.spot[data-slot="${key}"]`);

export const bell = (page: Page) => page.getByRole("button", { name: "Ring the bell" });

/**
 * Drags with real pointer events, the way a person does with the light pickup: press, nudge past
 * the 4 px activation distance, carry it to the spot, let go.
 */
export async function drag(page: Page, from: Locator, to: Locator) {
  await from.scrollIntoViewIfNeeded();
  const a = await from.boundingBox();
  const b = await to.boundingBox();
  if (!a || !b) throw new Error("drag source or target not visible");
  await page.mouse.move(a.x + a.width / 2, a.y + a.height / 2);
  await page.mouse.down();
  await page.mouse.move(a.x + a.width / 2 + 6, a.y + a.height / 2 + 6, { steps: 2 });
  await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2, { steps: 12 });
  await page.mouse.up();
  // dnd-kit ignores a new press until the drop animation (250 ms) has finished.
  await page.waitForTimeout(400);
}

/** Rings the bell and waits for that attempt's answer and for Penny to finish acting it out. */
export async function callAndWait(page: Page, call: () => Promise<void>) {
  const answered = page.waitForResponse((r) => r.url().includes("/v1/attempts"));
  await call();
  await answered;
  await expect(bell(page)).toBeEnabled();
}

/**
 * Plays Level 1 until a call wins. The stub teller refuses only the day's wary persona, so one
 * of the first two personas wins; `place` puts a prop on its spot the way the test's player
 * would (drag, keyboard, tap).
 */
export async function winLevel1(
  page: Page,
  place: (label: string, spotKey: string) => Promise<void>,
  call: () => Promise<void>,
) {
  await place("Urgent Fraud Alert", "pretext");
  await place("Authority Pressure", "technique");
  await place("Small Wire ($200)", "payload");
  for (const persona of ["Branch Manager", "Bank Examiner", "Fraud Department"]) {
    await place(persona, "persona");
    await expect(spot(page, "persona")).toContainText(persona);
    await callAndWait(page, call);
    const result = page.locator("#result-title");
    await expect(result).toHaveText(/The wire went through\.|Penny said no\.|Stopped by the bank's controls\./);
    if ((await result.textContent()) === "The wire went through.") return;
  }
  throw new Error("No persona won Level 1");
}
