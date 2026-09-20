import { defineConfig, devices } from "@playwright/test";

/**
 * E2E config. Assumes the Java API (port 8082) and the Postgres+pgvector
 * container (docker compose up -d db) are already running — see
 * docs/planning/local-development.md.
 *
 * `golden-path.spec.ts` additionally requires the Java API to be running
 * with the "dev" Spring profile active (SPRING_PROFILES_ACTIVE=dev
 * ./mvnw spring-boot:run), which registers the manual-QA-only
 * DevLoginController — see docs/authentication.md "Testing without a
 * Google account" and tests/e2e/dev-auth.ts. Every other spec file here
 * runs against the API in its normal (non-dev) profile.
 *
 * No OPENAI_API_KEY is required, and the chat-turn network call is
 * always stubbed in every spec that sends a message — this repo's local
 * `.env` may carry a real, billable key, and this suite's cost and
 * outcome must never depend on that.
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
