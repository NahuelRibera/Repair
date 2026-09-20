import { Page } from "@playwright/test";

/**
 * Signs a Playwright page in as a real, database-backed Repair user via
 * the manual-QA-only DevLoginController (see
 * apps/api/src/main/java/dev/repair/api/auth/DevLoginController.java and
 * docs/authentication.md "Testing without a Google account"). Requires
 * the Java API to be running with the "dev" Spring profile active
 * (SPRING_PROFILES_ACTIVE=dev) — see playwright.config.ts.
 *
 * This builds a real Spring Security session the same way a genuine
 * Google login would, so everything downstream (ownership, ApiAuthenticationEntryPoint,
 * AuthenticatedUserInterceptor) behaves identically to production — the
 * tests that use this are exercising the real backend, not a mock.
 */
export async function devLogin(page: Page, sub: string, email: string, displayName: string) {
  await page.goto("/");
  await page.waitForFunction(() => document.cookie.includes("XSRF-TOKEN="));
  await page.evaluate(
    async ({ sub, email, displayName }) => {
      const match = document.cookie.match(/XSRF-TOKEN=([^;]+)/);
      const csrfToken = match ? decodeURIComponent(match[1]) : null;
      const res = await fetch("/api/auth/dev-login", {
        method: "POST",
        credentials: "include",
        headers: {
          "Content-Type": "application/json",
          ...(csrfToken ? { "X-XSRF-TOKEN": csrfToken } : {}),
        },
        body: JSON.stringify({ sub, email, displayName }),
      });
      if (!res.ok) {
        throw new Error(`dev-login failed with status ${res.status}: ${await res.text()}`);
      }
    },
    { sub, email, displayName }
  );
}

/** A short, collision-resistant subject unique to this test run/worker. */
export function uniqueSub(label: string): string {
  return `pw-${label}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}
