import { expect, test } from "@playwright/test";

const MOCK_USER = {
  id: 1,
  googleSub: "google-fake-sub",
  email: "states@example.test",
  displayName: "States Rider",
  googlePictureUrl: null,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

test.describe("garage loading, empty and error states", () => {
  test.beforeEach(async ({ page }) => {
    await page.route("**/api/me", (route) => route.fulfill({ json: MOCK_USER }));
    await page.route("**/api/moto-sessions", (route) => route.fulfill({ json: [] }));
  });

  test("garage list shows an error with retry, then the empty state", async ({ page }) => {
    let calls = 0;
    await page.route("**/api/garage/vehicles", (route) => {
      if (route.request().method() !== "GET") return route.continue();
      calls += 1;
      if (calls === 1) return route.fulfill({ status: 500, json: { error: "boom" } });
      return route.fulfill({ json: [] });
    });

    await page.goto("/garage");
    await expect(page.getByRole("alert")).toHaveText("Couldn't load your garage.");
    await page.getByRole("button", { name: "Try again" }).click();
    await expect(page.getByText("You haven't added a motorcycle yet.")).toBeVisible();
    await expect(page.getByRole("heading", { level: 1, name: "My Garage" })).toBeVisible();
  });

  test("bike detail shows an error with retry and fits 360 px without horizontal scroll", async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 740 });
    const vehicle = {
      id: 7,
      manufacturerName: "Yamaha",
      modelName: "MT-07",
      year: 2023,
      nickname: null,
      currentOdometerKm: 1200,
    };
    let calls = 0;
    await page.route("**/api/garage/vehicles/7", (route) => {
      calls += 1;
      if (calls === 1) return route.fulfill({ status: 500, json: { error: "boom" } });
      return route.fulfill({ json: vehicle });
    });
    await page.route("**/api/garage/vehicles/7/dashboard", (route) => route.fulfill({ json: [] }));
    await page.route("**/api/garage/vehicles/7/maintenance", (route) => route.fulfill({ json: [] }));

    await page.goto("/garage/7");
    await expect(page.getByRole("alert")).toBeVisible();
    await page.getByRole("button", { name: "Try again" }).click();
    await expect(page.getByText("No maintenance recorded yet.")).toBeVisible();
    await expect(page.getByLabel("Current odometer (km)")).toBeVisible();

    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });
});
