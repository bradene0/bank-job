/// <reference lib="dom" />
import { test as base, expect } from "@playwright/test";

/**
 * Every test fails on a Content Security Policy violation. The compose stack serves the
 * production policy (nginx.conf mirrors public/_headers), so a blocked style, script or request
 * shows up here before it ships.
 */
export const test = base.extend<{ cspViolations: string[] }>({
  cspViolations: [
    async ({ page }, use) => {
      await page.addInitScript(() => {
        const seen: string[] = [];
        (window as unknown as { __cspViolations: string[] }).__cspViolations = seen;
        document.addEventListener("securitypolicyviolation", (e) => {
          seen.push(`${e.effectiveDirective} blocked ${e.blockedURI || "inline"}`);
        });
      });
      const violations: string[] = [];
      await use(violations);
      for (const frame of page.isClosed() ? [] : page.frames()) {
        const seen = await frame
          .evaluate(() => (window as unknown as { __cspViolations?: string[] }).__cspViolations ?? [])
          .catch(() => []);
        violations.push(...seen);
      }
      expect(violations, "CSP violations").toEqual([]);
    },
    { auto: true },
  ],
});

export { expect };
