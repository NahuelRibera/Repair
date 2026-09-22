import { expect, Page, test } from "@playwright/test";
import { devLogin, uniqueSub } from "./dev-auth";

/**
 * Reproduces the reported "Repair understands it in chat but Garage never
 * shows it" bug for a real Yamaha Ténéré 700 World Raid (2026), end to
 * end against the real backend and a real local Postgres row.
 *
 * Scope note: this suite never makes a real OpenAI call (see
 * golden-path.spec.ts's header comment — the repo's local .env carries a
 * real, billable key). Whether the model correctly turns a rider's
 * message into a structured proposedMaintenanceEvents entry is therefore
 * NOT what this file tests — that's covered by
 * MotoChatOrchestrationServiceTest's mocked-model unit tests and by
 * manual live QA with the real model (see the session's final report).
 * What this file tests deterministically, against the real backend, is
 * everything AFTER a proposal is confirmed: the same
 * MaintenanceRepository.createEvent / GarageVehicleRepository write path
 * a chat-confirmed action uses is exercised directly via the real REST
 * API (the same one the manual "+ Record maintenance" form uses), then
 * the My Garage UI is asserted against real persisted rows — across a
 * reload and a brand-new chat session for the same physical vehicle.
 */

function xsrfToken(page: Page) {
  return page.evaluate(() => decodeURIComponent(document.cookie.match(/XSRF-TOKEN=([^;]+)/)?.[1] ?? ""));
}

async function pickAYamahaBike(page: Page, modelSearch: string) {
  await page.getByRole("button", { name: "Select manufacturer" }).click();
  await page.getByPlaceholder("Type to search, or browse below…").fill("Yamaha");
  await page.getByRole("option", { name: "Yamaha", exact: true }).click();

  await page.getByRole("button", { name: "Select model" }).click();
  await page.getByPlaceholder("Type to search, or browse below…").fill(modelSearch);
  await page.getByRole("option", { name: modelSearch, exact: true }).click();

  const yearSelect = page.getByLabel("Year");
  await expect(async () => {
    const opts = await yearSelect.locator("option").allTextContents();
    expect(opts.some((y) => /^\d{4}$/.test(y.trim()))).toBe(true);
  }).toPass({ timeout: 10_000 });
  const opts = await yearSelect.locator("option").allTextContents();
  const year = opts.find((y) => /^\d{4}$/.test(y.trim()))!.trim();
  await yearSelect.selectOption({ label: year });
  return Number(year);
}

test.describe("maintenance persistence survives reload and a brand-new chat", () => {
  test("multiple real maintenance events + odometer show up correctly in My Garage", async ({ page }) => {
    await devLogin(page, uniqueSub("maint-persist"), "maint-persist@example.test", "Maintenance Persistence Rider");
    await page.goto("/");

    await pickAYamahaBike(page, "Ténéré 700 World Raid");
    await page.getByRole("button", { name: "Start with this bike →" }).click();
    await expect(page).toHaveURL(/\/chat\/\d+$/);
    const sessionId = Number(page.url().match(/\/chat\/(\d+)$/)![1]);

    const csrf = await xsrfToken(page);
    const sessionDetail = await page.request.get(`/api/moto-sessions/${sessionId}`).then((r) => r.json());
    const vehicleId = sessionDetail.session.garageVehicleId as number;

    // Same write path a chat-confirmed proposal uses (MaintenanceRepository.createEvent /
    // GarageVehicleController.update) — exercised directly so this test never depends on
    // a real LLM call. Values match the reported QA sequence exactly.
    await page.request.patch(`/api/garage/vehicles/${vehicleId}`, {
      headers: { "X-XSRF-TOKEN": csrf },
      data: { currentOdometerKm: 25000 },
    });
    for (const event of [
      { serviceType: "ENGINE_OIL_CHANGE", odometerKm: 20000, notes: null },
      { serviceType: "OIL_FILTER_CHANGE", odometerKm: 12000, notes: null },
      { serviceType: "AIR_FILTER_CHANGE", odometerKm: 23000, notes: null },
      { serviceType: "TIRE_REPLACEMENT", odometerKm: 23300, notes: "Rear tire — Dunlop Trail Max Raid" },
    ]) {
      const res = await page.request.post(`/api/garage/vehicles/${vehicleId}/maintenance`, {
        headers: { "X-XSRF-TOKEN": csrf },
        data: event,
      });
      expect(res.ok(), `creating ${event.serviceType} failed: ${res.status()}`).toBe(true);
    }

    await page.goto(`/garage/${vehicleId}`);

    async function assertGaragePageState() {
      const odometerInput = page.getByText("Current odometer (km)").locator("..").locator("input");
      await expect(odometerInput).toHaveValue("25000");

      // Tires has no verified interval (condition-based) but the completed
      // replacement is still real history — status card must show "Last"
      // alongside the Condition-based badge (the reported bug: history used
      // to disappear entirely for any card without an interval).
      const tiresCard = page.getByText("Tires").locator("..").locator("..");
      await expect(tiresCard.getByText("Condition-based")).toBeVisible();
      await expect(tiresCard.getByText("Last: 23,300 km")).toBeVisible();

      const oilFilterCard = page.getByText("Oil filter", { exact: true }).locator("..").locator("..");
      await expect(oilFilterCard.getByText("Last: 12,000 km")).toBeVisible();

      const airFilterCard = page.getByText("Air filter", { exact: true }).locator("..").locator("..");
      await expect(airFilterCard.getByText("Last: 23,000 km")).toBeVisible();

      const engineOilCard = page.getByText("Engine oil", { exact: true }).locator("..").locator("..");
      await expect(engineOilCard.getByText("Last: 20,000 km")).toBeVisible();

      // Spark plugs genuinely has no recorded maintenance history.
      // Regardless of whether this bike has a verified interval, the UI must
      // never fabricate a completed replacement.
      const sparkPlugCard = page.getByText("Spark plugs", { exact: true }).locator("..").locator("..");
      await expect(sparkPlugCard.getByText(/^Last:/)).toHaveCount(0);

      await expect(page.getByText("23,300 km · Rear tire — Dunlop Trail Max Raid")).toBeVisible();
      await expect(page.getByText("Air filter").first()).toBeVisible();
    }

    await assertGaragePageState();

    await page.reload();
    await assertGaragePageState();

    // A brand-new conversation for the SAME physical vehicle must be able
    // to start at all and be correctly associated with it — the deeper
    // "does the model's own context block include this history" part is
    // covered by MotoChatOrchestrationServiceTest / buildOwnershipBlock,
    // not re-tested here since it would require a real model call.
    await page.getByRole("button", { name: "Ask Repair →" }).click();
    await expect(page).toHaveURL(/\/chat\/\d+$/);
    const newSessionId = Number(page.url().match(/\/chat\/(\d+)$/)![1]);
    expect(newSessionId).not.toBe(sessionId);
    const newSessionDetail = await page.request.get(`/api/moto-sessions/${newSessionId}`).then((r) => r.json());
    expect(newSessionDetail.session.garageVehicleId).toBe(vehicleId);
  });
});
