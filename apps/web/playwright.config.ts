import { defineConfig, devices } from "@playwright/test";

/**
 * E2E config. Assumes the Java API (port 8082) and the Postgres+pgvector
 * container (docker compose up -d db) are already running — see
 * docs/planning/local-development.md. No OPENAI_API_KEY is required: the
 * golden path below exercises the API's own graceful "AI not configured"
 * response, which is real application behavior, not a stub.
 */
export default defineConfig({
  testDir: "./tests/e2e",
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL: "http://localhost:3000",
    trace: "retain-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: {
    command: "npm run dev",
    url: "http://localhost:3000",
    reuseExistingServer: true,
    timeout: 30_000,
  },
});
