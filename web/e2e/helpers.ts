import { expect, type Locator, type Page } from "@playwright/test";

export const API = process.env.API_URL ?? "http://localhost:8080";

export const tile = (page: Page, label: string) =>
  page.getByRole("button", { name: new RegExp(`^${label.replace(/[()$]/g, "\\$&")}`) });

export const slot = (page: Page, key: string) => page.locator(`.slot[data-slot="${key}"]`);

/** Drags with real pointer events: press, move past the activation distance, move, release. */
export async function drag(page: Page, from: Locator, to: Locator) {
  // Center it, clear of the sticky con bar and call bar.
  await from.evaluate((el) => el.scrollIntoView({ block: "center" }));
  const a = await from.boundingBox();
  const b = await to.boundingBox();
  if (!a || !b) throw new Error("drag source or target not visible");
  await page.mouse.move(a.x + a.width / 2, a.y + a.height / 2);
  await page.mouse.down();
  // Like a person: press, hold a moment, then move. (dnd-kit's default mouse activation waits
  // 200 ms, or for 5 px of movement, and aborts on a fast jerk.)
  await page.waitForTimeout(250);
  await page.mouse.move(a.x + a.width / 2 + 6, a.y + a.height / 2 + 6, { steps: 3 });
  await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2, { steps: 12 });
  await page.mouse.up();
  // dnd-kit ignores a new press until the drop animation (250 ms) has finished.
  await page.waitForTimeout(400);
}

/**
 * Plays Level 1 until a call wins. The stub teller refuses only the day's wary persona, so one
 * of the first two personas wins; `place` puts a tile in its slot the way the test's player
 * would (drag, keyboard, tap).
 */
/** Makes the call and waits for that attempt's answer (the old result is on screen meanwhile). */
export async function callAndWait(page: Page, call: () => Promise<void>) {
  const answered = page.waitForResponse((r) => r.url().includes("/v1/attempts"));
  await call();
  await answered;
  await expect(page.getByRole("button", { name: "Make the call" })).toBeEnabled();
}

export async function winLevel1(page: Page, place: (label: string, slotKey: string) => Promise<void>, call: () => Promise<void>) {
  await place("Urgent Fraud Alert", "pretext");
  await place("Authority Pressure", "technique");
  await place("Small Wire ($200)", "payload");
  for (const persona of ["Branch Manager", "Bank Examiner", "Fraud Department"]) {
    await place(persona, "persona");
    await expect(slot(page, "persona")).toContainText(persona);
    await callAndWait(page, call);
    const result = page.locator("#result-title");
    await expect(result).toHaveText(/The wire went through\.|Penny said no\.|Stopped by the bank's controls\./);
    if ((await result.textContent()) === "The wire went through.") return;
  }
  throw new Error("No persona won Level 1");
}
