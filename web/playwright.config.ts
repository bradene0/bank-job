import { defineConfig, devices } from "@playwright/test";

// Runs against a running game: the compose stack by default (web on 5173, game-api on 8080).
// Live tests need the stack; replay tests cut the API off themselves.
export default defineConfig({
  testDir: "e2e",
  timeout: 120_000,
  expect: { timeout: 15_000 },
  fullyParallel: false, // one stack, one stub teller: keep attempts in order
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: process.env.BASE_URL ?? "http://localhost:5173",
    trace: "retain-on-failure",
  },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"] } },
    // A phone: touch input and a narrow viewport (Chromium with Pixel 7 emulation).
    { name: "phone", use: { ...devices["Pixel 7"] } },
    // Safari's engine on an iPhone-sized touch screen.
    { name: "webkit-phone", use: { ...devices["iPhone 15"] } },
    // A player who asked the OS for less motion.
    { name: "reduced-motion", use: { ...devices["Desktop Chrome"], reducedMotion: "reduce" } },
  ],
});
