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

// Requests fail until "Try again" is clicked rather than for the first call only: under
// `next dev`, React Strict Mode runs each effect twice, so a count-based mock lets the
// duplicate initial request succeed and the error state never renders.
test.describe("garage loading, empty and error states", () => {
  test.beforeEach(async ({ page }) => {
    await page.route("**/api/me", (route) => route.fulfill({ json: MOCK_USER }));
    await page.route("**/api/moto-sessions", (route) => route.fulfill({ json: [] }));
  });

  test("garage list shows an error with retry, then the empty state", async ({ page }) => {
    let failing = true;
    await page.route("**/api/garage/vehicles", (route) => {
      if (route.request().method() !== "GET") return route.continue();
      if (failing) return route.fulfill({ status: 500, json: { error: "boom" } });
      return route.fulfill({ json: [] });
    });

    await page.goto("/garage");
    await expect(page.getByText("Couldn't load your garage.")).toBeVisible();
    failing = false;
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
    let failing = true;
    await page.route("**/api/garage/vehicles/7", (route) => {
      if (failing) return route.fulfill({ status: 500, json: { error: "boom" } });
      return route.fulfill({ json: vehicle });
    });
    await page.route("**/api/garage/vehicles/7/dashboard", (route) => route.fulfill({ json: [] }));
    await page.route("**/api/garage/vehicles/7/maintenance", (route) => route.fulfill({ json: [] }));

    await page.goto("/garage/7");
    await expect(page.getByRole("button", { name: "Try again" })).toBeVisible();
    failing = false;
    await page.getByRole("button", { name: "Try again" }).click();
    await expect(page.getByText("No maintenance recorded yet.")).toBeVisible();
    await expect(page.getByLabel("Current odometer (km)")).toBeVisible();

    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });
});
