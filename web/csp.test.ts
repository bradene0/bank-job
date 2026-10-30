// @vitest-environment node
import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

// The compose stack's nginx serves the production policy so end-to-end tests run under it; only
// connect-src differs (the local API instead of the public one).
const read = (file: string) => readFileSync(path.join(import.meta.dirname, file), "utf8");

function directives(policy: string): Map<string, string> {
  return new Map(
    policy
      .split(";")
      .map((d) => d.trim())
      .filter(Boolean)
      .map((d) => {
        const [name = "", ...values] = d.split(/\s+/);
        return [name, values.join(" ")];
      }),
  );
}

describe("Content Security Policy", () => {
  const production = directives(/Content-Security-Policy: (.*)/.exec(read("public/_headers"))?.[1] ?? "");
  const local = directives(/add_header Content-Security-Policy "(.*)" always;/.exec(read("nginx.conf"))?.[1] ?? "");

  it("is the same in production and in the compose stack, apart from the API origin", () => {
    expect(production.get("connect-src")).toBe("'self' https://bankjob-live.bradenevans.dev");
    expect(local.get("connect-src")).toBe("'self' http://localhost:8080");
    production.delete("connect-src");
    local.delete("connect-src");
    expect(local).toEqual(production);
  });

  it("allows no inline script or style beyond the hashed ones", () => {
    expect(production.get("script-src")).toBe("'self'");
    expect(production.get("style-src")).not.toMatch(/unsafe/);
  });
});
