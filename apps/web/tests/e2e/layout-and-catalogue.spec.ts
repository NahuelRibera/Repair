import { expect, Page, test } from "@playwright/test";

/**
 * REWRITTEN/TRIMMED for the motorcycle product and its Google-auth
 * productization pass (2026-09-15). Audited test by test against the
 * current app (see docs/planning/status.md for the summary):
 *
 * - "chat layout: independent scrolling" (kept, rewritten): the layout
 *   bug this guarded against (whole page scrolling, hiding the sidebar)
 *   is about ChatShell's CSS architecture, not car-vs-moto content, and
 *   that architecture is unchanged and still worth protecting. Rewritten
 *   to use a mocked signed-in session and a mocked long conversation
 *   (see mockSignedInWithConversation below) instead of seeding
 *   `diagnostic_messages` (a car-prototype table) via `docker exec psql`
 *   — mocking the moto-sessions detail response directly is simpler,
 *   faster, has no database dependency, and exercises the exact same
 *   frontend rendering path.
 *
 * - "catalogue selectors: browsing beyond the first page" — the
 *   *keyboard navigation* test is kept, rewritten to use the real
 *   motorcycle catalogue (public, no auth needed). The other two tests
 *   in this group ("a manufacturer/model reachable without typing, past
 *   position ~20/~30") are DELETED: they guarded against a car-catalogue
 *   backend endpoint silently paginating/capping its results — the
 *   motorcycle catalogue endpoints
 *   (MotorcycleCatalogController/BikePicker.fetchManufacturers/fetchModels)
 *   have no pagination at all, so that bug class cannot recur here, and
 *   the real seeded catalogue (one manufacturer, five models) is too
 *   small to construct an equivalent "past a small page size" scenario
 *   without fabricating fake catalogue data — which would just be
 *   re-testing that Combobox renders whatever list it's given, already
 *   covered by the keyboard-navigation test below.
 *
 * - "demo vehicle identification" (both tests) — DELETED. This tested a
 *   car-prototype-only UI concept (a single spotlighted "supported demo
 *   vehicle" panel + an example-question prefill button, backed by
 *   `repair.demo.*` properties) that was not carried over to the
 *   motorcycle product by design: the moto catalogue is fully dynamic,
 *   every ingested bike is equally "supported," and there is no
 *   single-vehicle showcase panel anywhere in the current landing page
 *   or chat picker. There is no motorcycle equivalent to rewrite this
 *   into.
 */

const MOCK_USER = {
  id: 1,
  googleSub: "google-fake-sub",
  email: "rider@example.test",
  displayName: "Test Rider",
  googlePictureUrl: null,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

const SESSION_ID = 4242;

function seededMessages(pairCount: number) {
  const createdAt = new Date().toISOString();
  const messages = [];
  for (let i = 1; i <= pairCount; i++) {
    messages.push({ id: i * 2, role: "user", content: `Seeded test message ${i}`, structuredResponseJson: null, createdAt });
    messages.push({ id: i * 2 + 1, role: "assistant", content: `Seeded test reply ${i}`, structuredResponseJson: null, createdAt });
  }
  return messages;
}

async function mockSignedInWithConversation(page: Page, pairCount: number, sidebarSessionCount: number) {
  await page.route("**/api/me", (route) => route.fulfill({ json: MOCK_USER }));

  const sidebarEntries = Array.from({ length: sidebarSessionCount }, (_, i) => ({
    id: 1000 + i,
    title: null,
    garageVehicleId: 1,
    manufacturerName: "Yamaha",
    modelName: "MT-07",
    year: 2023,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  }));
  await page.route("**/api/moto-sessions", (route) => {
    if (route.request().method() !== "GET") return route.continue();
    route.fulfill({ json: sidebarEntries });
  });

  await page.route(`**/api/moto-sessions/${SESSION_ID}`, (route) => {
    if (route.request().method() !== "GET") return route.continue();
    route.fulfill({
      json: {
        session: {
          id: SESSION_ID,
          title: null,
          garageVehicleId: 1,
          manufacturerName: "Yamaha",
          modelName: "MT-07",
          year: 2023,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
        messages: seededMessages(pairCount),
      },
    });
  });
}

test.describe("chat layout: independent scrolling", () => {
  test("scrolling a long conversation keeps the sidebar, header, and composer in place", async ({ page }) => {
    await mockSignedInWithConversation(page, 25, 1);
    await page.goto(`/chat/${SESSION_ID}`);

    // No outer page scrollbar: the document itself must not be taller
    // than the viewport for this route (only the message list and the
    // sidebar list scroll internally).
    const documentScrollable = await page.evaluate(
      () => document.documentElement.scrollHeight > document.documentElement.clientHeight + 5
    );
    expect(documentScrollable).toBe(false);

    // Loads scrolled to the latest message, not the oldest.
    await expect(page.getByText("Seeded test reply 25")).toBeVisible();

    const newChatButton = page.getByRole("link", { name: "+ New chat" });
    const header = page.getByRole("banner");
    await expect(newChatButton).toBeInViewport();
    await expect(header).toBeInViewport();

    // Scroll the message list specifically (not the window) up to the
    // oldest message, then confirm the sidebar and header never moved.
    await page.getByText("Seeded test reply 1", { exact: true }).scrollIntoViewIfNeeded();
    await expect(page.getByText("Seeded test message 1", { exact: true })).toBeVisible();
    await expect(newChatButton).toBeInViewport();
    await expect(header).toBeInViewport();
    await expect(page.getByPlaceholder("Ask about maintenance, or describe what's happening…")).toBeInViewport();
  });

  test("scrolling the sidebar conversation list does not move the open conversation", async ({ page }) => {
    await mockSignedInWithConversation(page, 5, 20);
    await page.goto(`/chat/${SESSION_ID}`);
    await expect(page.getByText("Seeded test reply 5")).toBeVisible();

    const sidebarList = page.locator("aside ul, aside div.overflow-y-auto").first();
    await sidebarList.hover();
    await page.mouse.wheel(0, 800);

    // The conversation is still scrolled to its own bottom — the sidebar
    // scroll must not have scrolled the message area back to the top.
    await expect(page.getByText("Seeded test reply 5")).toBeVisible();
  });
});

test.describe("catalogue selectors", () => {
  test("keyboard navigation selects an option without a mouse", async ({ page }) => {
    // Real motorcycle catalogue, no mocking needed — these endpoints are
    // public (see SecurityConfig).
    await page.route("**/api/me", (route) => route.fulfill({ status: 401, json: { error: "unauthenticated" } }));
    await page.goto("/");

    await page.getByRole("button", { name: "Select manufacturer" }).click();
    const input = page.getByPlaceholder("Type to search, or browse below…");
    await input.fill("Yamaha");
    await expect(page.getByRole("option", { name: "Yamaha", exact: true })).toBeVisible();
    await input.press("ArrowDown");
    await input.press("Enter");
    await expect(page.getByRole("button", { name: "Yamaha", exact: true })).toBeVisible();
  });
});
