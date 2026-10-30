import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";
import { bankjobContent } from "./content-plugin.ts";

export default defineConfig({
  plugins: [react(), bankjobContent()],
  test: {
    environment: "jsdom",
    setupFiles: ["./src/setupTests.ts"],
    include: ["src/**/*.test.{ts,tsx}", "content-plugin.test.ts", "csp.test.ts"],
  },
});
