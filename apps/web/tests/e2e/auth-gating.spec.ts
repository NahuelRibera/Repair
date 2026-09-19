import { expect, Page, test } from "@playwright/test";

/**
 * Small smoke suite for the auth-gating flow added in the productization
 * pass — see docs/authentication.md. Uses mocked/test authentication (no
 * live Google account needed, and no real Java backend call for the
 * protected endpoints): `/api/me` and the garage/session endpoints are
 * intercepted at the browser network layer, exactly like golden-path.spec.ts
 * does for the chat-turn call. `/api/auth/login` itself is also
 * intercepted so the test never actually tries to reach Google — it only
 * asserts the app *attempted* the right navigation with the right `next`.
 *
 * Deliberately small: this proves the frontend's own gating/redirect/
 * pending-selection logic, not the real OAuth2 wiring (that's covered by
 * SecurityConfigIT and the other Java auth tests) or the visual design.
 */

const FAKE_USER = {
  id: 1,
  googleSub: "google-fake-sub",
  email: "rider@example.test",
  displayName: "Test Rider",
  googlePictureUrl: null,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

async function mockSignedOut(page: Page) {
  await page.route("**/api/me", (route) => route.fulfill({ status: 401, json: { error: "unauthenticated" } }));
}

async function mockSignedIn(page: Page) {
  await page.route("**/api/me", (route) => route.fulfill({ json: FAKE_USER }));
}

/** Stands in for the real Java backend's login redirect chain — real
 * Google credentials are never available in a test environment, so this
 * only proves the frontend requested the right destination. */
async function interceptLoginNavigation(page: Page) {
  await page.route("**/api/auth/login**", (route) =>
    route.fulfill({ status: 200, contentType: "text/html", body: "<html><body>stubbed login</body></html>" })
  );
}

test.describe("landing page stays public", () => {
  test("renders and offers Sign in when signed out", async ({ page }) => {
    await mockSignedOut(page);
    await page.goto("/");
    await expect(page.getByRole("heading", { name: /keep it running\. keep riding\./i })).toBeVisible();
    await expect(page.getByRole("link", { name: "Sign in" })).toBeVisible();
  });
});

test.describe("protected routes redirect to login when signed out", () => {
  test("visiting /garage directly redirects to /api/auth/login with the right next", async ({ page }) => {
    await mockSignedOut(page);
    await interceptLoginNavigation(page);

    await page.goto("/garage");
    await page.waitForURL(/\/api\/auth\/login\?next=/);
    expect(page.url()).toContain("next=%2Fgarage");
  });

  test("visiting /chat directly redirects to /api/auth/login with the right next", async ({ page }) => {
    await mockSignedOut(page);
    await interceptLoginNavigation(page);

    await page.goto("/chat");
    await page.waitForURL(/\/api\/auth\/login\?next=/);
    expect(page.url()).toContain("next=%2Fchat");
  });
});

test.describe("landing bike selection preserved across login", () => {
  test("picking a bike while signed out saves the selection and redirects to login", async ({ page }) => {
    await mockSignedOut(page);
    await interceptLoginNavigation(page);
    await page.route("**/api/garage/vehicles", (route) => {
      if (route.request().method() === "POST") {
        return route.fulfill({ status: 401, json: { error: "unauthenticated" } });
      }
      return route.continue();
    });

    await page.goto("/");
    await page.getByRole("button", { name: "Select manufacturer" }).click();
    await page.getByPlaceholder("Type to search, or browse below…").fill("Yamaha");
    await page.getByRole("option", { name: "Yamaha", exact: true }).click();

    await page.getByRole("button", { name: "Select model" }).click();
    await page.getByPlaceholder("Type to search, or browse below…").fill("MT-07");
    await page.getByRole("option", { name: "MT-07", exact: true }).click();

    const yearSelect = page.getByLabel("Year");
    await expect(async () => {
      const options = await yearSelect.locator("option").allTextContents();
      expect(options.some((y) => /^\d{4}$/.test(y.trim()))).toBe(true);
    }).toPass({ timeout: 10_000 });
    const yearOptions = await yearSelect.locator("option").allTextContents();
    const year = yearOptions.find((y) => /^\d{4}$/.test(y.trim()))!.trim();
    await yearSelect.selectOption({ label: year });

    await page.getByRole("button", { name: "Start with this bike →" }).click();
    await page.waitForURL(/\/api\/auth\/login\?next=%2Fchat/);

    const pending = await page.evaluate(() => localStorage.getItem("repair.pendingBikeSelection"));
    expect(pending).not.toBeNull();
    const parsed = JSON.parse(pending!);
    expect(parsed.year).toBe(Number(year));
    expect(typeof parsed.modelId).toBe("number");
  });
});

test.describe("signed-in experience", () => {
  test("My Garage loads normally instead of redirecting when signed in", async ({ page }) => {
    await mockSignedIn(page);
    await page.route("**/api/garage/vehicles", (route) => route.fulfill({ json: [] }));
    await page.route("**/api/moto-sessions", (route) => route.fulfill({ json: [] }));

    await page.goto("/garage");
    await expect(page.getByRole("heading", { name: "My Garage" })).toBeVisible();
    await expect(page).toHaveURL(/\/garage$/);
  });

  test("the account menu shows the signed-in rider and a working sign-out control", async ({ page }) => {
    await mockSignedIn(page);
    await page.route("**/api/garage/vehicles", (route) => route.fulfill({ json: [] }));
    await page.route("**/api/moto-sessions", (route) => route.fulfill({ json: [] }));
    await page.route("**/api/auth/logout", (route) => route.fulfill({ status: 204, body: "" }));

    await page.goto("/garage");
    await page.getByRole("button", { name: "Account menu" }).click();
    await expect(page.getByText(FAKE_USER.email)).toBeVisible();
    await expect(page.getByRole("menuitem", { name: "My Garage" })).toBeVisible();
    await expect(page.getByRole("menuitem", { name: "Sign out" })).toBeVisible();
  });
});
