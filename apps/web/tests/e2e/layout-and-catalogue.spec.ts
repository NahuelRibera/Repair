import { execSync } from "node:child_process";
import { expect, Page, test } from "@playwright/test";

/**
 * Regression tests for three concrete bugs fixed in this pass:
 *  1. The whole page scrolled with the conversation, hiding the sidebar.
 *  2. Manufacturer/model dropdowns silently capped at a small page size,
 *     making most of the real catalogue unreachable by browsing.
 *  3. The "supported demo vehicle" panel and its example buttons resolved
 *     to the wrong variant (the first row of a query sorted by year
 *     ascending, not the curated BMW E90 320d).
 *
 * No OpenAI calls are made anywhere in this file. Test 1 needs a long
 * conversation to actually exercise scrolling — seeded via direct SQL
 * against the local dev database (bypassing the chat API entirely, so it
 * costs nothing), the same way this was verified manually during
 * development. Requires the local `repair-v2-db` container to be running.
 */

function seedLongConversation(sessionId: number, messageCount: number) {
  const values: string[] = [];
  for (let i = 1; i <= messageCount; i++) {
    values.push(
      `(${sessionId}, 'user', 'Seeded test message ${i}', now() + interval '${i} seconds')`
    );
    values.push(
      `(${sessionId}, 'assistant', 'Seeded test reply ${i}', now() + interval '${i} seconds' + interval '1 second')`
    );
  }
  const sql = `INSERT INTO diagnostic_messages (session_id, role, content, created_at) VALUES ${values.join(",")};`;
  // No "-i": the SQL is passed as a -c argument, not piped over stdin, and
  // an interactive docker exec here has occasionally raced the subsequent
  // page navigation in this suite.
  execSync(`docker exec repair-v2-db psql -U repair_v2 -d repair_v2 -v ON_ERROR_STOP=1 -c "${sql.replace(/"/g, '\\"')}"`, {
    stdio: "pipe",
  });
}

/**
 * Creates a session via a direct fetch (cheaper than driving the full
 * VehiclePicker UI for tests that don't care about that flow). Must wait
 * for the sidebar's own initial `/api/sessions` fetch to settle first: on
 * a brand-new browser context neither request yet carries the anonymous
 * `repair_visitor` cookie, so firing both at once is a real race — the
 * backend mints a different visitor id for each, and whichever response
 * lands last silently overwrites the other's cookie, orphaning the
 * session this helper just created under the visitor id that lost.
 */
async function createSession(page: Page, variantId = 23079): Promise<number> {
  // Each Playwright test gets a fresh browser context, so the sidebar's
  // own first fetch always resolves to an empty list before anything else
  // has had a chance to create a session.
  await expect(page.getByText("No conversations yet.")).toBeVisible();
  return page.evaluate(async (id) => {
    const res = await fetch("/api/sessions", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify({ variantId: id }),
    });
    const data = await res.json();
    return data.session.id;
  }, variantId);
}

test.describe("chat layout: independent scrolling", () => {
  test("scrolling a long conversation keeps the sidebar, header, and composer in place", async ({ page }) => {
    await page.goto("/chat");
    const sessionId = await createSession(page);
    seedLongConversation(sessionId, 25);
    await page.goto(`/chat/${sessionId}`);

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
    await expect(page.getByPlaceholder("Describe the symptom in detail…")).toBeInViewport();
  });

  test("scrolling the sidebar conversation list does not move the open conversation", async ({ page }) => {
    await page.goto("/chat");
    const sessionId = await createSession(page);
    seedLongConversation(sessionId, 5);

    // Enough sibling sessions to make the sidebar list itself scrollable.
    // The cookie race that createSession() guards against is now moot —
    // the visitor id is already established by the first createSession()
    // call above — so these can fire straight from page.evaluate.
    for (let i = 0; i < 20; i++) {
      await page.evaluate(
        async (variantId) => {
          await fetch("/api/sessions", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            credentials: "include",
            body: JSON.stringify({ variantId }),
          });
        },
        23079
      );
    }

    await page.goto(`/chat/${sessionId}`);
    await expect(page.getByText("Seeded test reply 5")).toBeVisible();

    const sidebarList = page.locator("aside ul, aside div.overflow-y-auto").first();
    await sidebarList.hover();
    await page.mouse.wheel(0, 800);

    // The conversation is still scrolled to its own bottom — the sidebar
    // scroll must not have scrolled the message area back to the top.
    await expect(page.getByText("Seeded test reply 5")).toBeVisible();
  });
});

test.describe("catalogue selectors: browsing beyond the first page", () => {
  test("a manufacturer sorted well past a small page size is reachable without typing", async ({ page }) => {
    await page.goto("/chat");
    await page.getByRole("button", { name: "Select manufacturer" }).click();
    // No search text typed — this is browsing, not searching. "Toyota"
    // sorts well past position 20 alphabetically among 112 manufacturers.
    const listbox = page.getByRole("listbox");
    await expect(listbox).toBeVisible();
    await listbox.locator('[role="option"]', { hasText: "Toyota" }).scrollIntoViewIfNeeded();
    await expect(listbox.getByRole("option", { name: "Toyota", exact: true })).toBeVisible();
  });

  test("BMW 3 Series Sedan is reachable in the model dropdown without a search query", async ({ page }) => {
    await page.goto("/chat");
    await page.getByRole("button", { name: "Select manufacturer" }).click();
    await page.getByPlaceholder("Type to search, or browse below…").fill("BMW");
    await page.getByRole("option", { name: "BMW", exact: true }).click();

    await page.getByRole("button", { name: "Select model" }).click();
    // Deliberately clear/skip the search box — "BMW 3 Series Sedan" sits
    // around position 30 of ~100 BMW models sorted alphabetically.
    const listbox = page.getByRole("listbox");
    await expect(listbox).toBeVisible();
    await listbox.getByRole("option", { name: "BMW 3 Series Sedan", exact: true }).scrollIntoViewIfNeeded();
    await expect(listbox.getByRole("option", { name: "BMW 3 Series Sedan", exact: true })).toBeVisible();
  });

  test("keyboard navigation selects an option without a mouse", async ({ page }) => {
    await page.goto("/chat");
    await page.getByRole("button", { name: "Select manufacturer" }).click();
    const input = page.getByPlaceholder("Type to search, or browse below…");
    await input.fill("BMW");
    await expect(page.getByRole("option", { name: "BMW", exact: true })).toBeVisible();
    await input.press("ArrowDown");
    await input.press("Enter");
    await expect(page.getByRole("button", { name: "BMW", exact: true })).toBeVisible();
  });
});

test.describe("demo vehicle identification", () => {
  test("the supported demo panel names the verified E90 320d, and its example button opens that exact variant", async ({ page }) => {
    await page.goto("/chat");
    const panel = page.getByText("Supported demo vehicle").locator("..");
    await expect(panel.getByText(/\(E90\) 320d 6MT RWD \(177 HP\)/)).toBeVisible();

    // The example button carries a "prefill" that auto-sends on landing in
    // the new session — stub that one call so this test (which only cares
    // about which variant it lands on, not the reply) never reaches the
    // real chat pipeline. Without this, whether the app happens to have a
    // live OPENAI_API_KEY configured locally would silently decide whether
    // this test makes a real, billed OpenAI call.
    await page.route("**/api/sessions/*/messages", (route) =>
      route.fulfill({
        json: {
          messageId: 999001,
          answer: {
            answerType: "insufficient_evidence",
            summary: "stubbed for test",
            confirmedSymptoms: [],
            followUpQuestions: [],
            hypotheses: [],
            safeChecks: [],
            cautions: [],
            missingInformation: [],
            sourceChunkIds: [],
          },
          evidence: [],
          debug: {
            requestId: "00000000-0000-0000-0000-000000000000",
            variantId: 23079,
            embeddingModel: null,
            generationModel: null,
            promptTokens: null,
            completionTokens: null,
            retrievalMillis: null,
            generationMillis: null,
            providerStatus: "missing_key",
            errorDetail: null,
          },
        },
      })
    );

    await page.getByRole("button", { name: /The driver's window won't go up/ }).click();
    await expect(page).toHaveURL(/\/chat\/\d+/);
    const header = page.getByRole("banner");
    await expect(header.getByText("BMW 3 Series (E90) 320d 6MT RWD (177 HP)")).toBeVisible();
  });

  test("the landing page and in-app panel describe the same demo vehicle", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByText(/BMW 3 Series \(E90\) 320d has full scenario coverage/)).toBeVisible();

    await page.goto("/chat");
    const panel = page.getByText("Supported demo vehicle").locator("..");
    await expect(panel.getByText(/\(E90\) 320d 6MT RWD \(177 HP\)/)).toBeVisible();
  });
});
