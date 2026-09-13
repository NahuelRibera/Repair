import { expect, Page, test } from "@playwright/test";

/**
 * End-to-end golden path against the real Next.js app and the real Java
 * API (see playwright.config.ts) for everything except the chat turn
 * itself, which is stubbed at the browser network layer (see
 * stubInsufficientEvidenceTurn below). This test verifies the full
 * catalogue -> session -> chat -> persistence path, not the OpenAI
 * integration itself (that's covered by mocked Java unit tests instead).
 *
 * The stub exists so this test's cost and outcome never depend on whether
 * a local .env happens to carry a real OPENAI_API_KEY: without it, this
 * would either make a real, billed OpenAI call (if a key is configured)
 * or silently assert on the "AI not configured" fallback text (if not) —
 * neither of which this suite should require to verify the persistence
 * path.
 */

const STUBBED_ANSWER = {
  answerType: "insufficient_evidence",
  summary:
    "OpenAI is not configured on this server (OPENAI_API_KEY is unset), so I can't run a live " +
    "diagnosis right now. The vehicle catalogue and data-quality views still work without it.",
  confirmedSymptoms: [],
  followUpQuestions: [],
  hypotheses: [],
  safeChecks: [],
  cautions: [],
  missingInformation: [],
  sourceChunkIds: [],
};

/**
 * Intercepts the chat-turn network calls for one session so no request
 * ever reaches the Java backend (and therefore never reaches OpenAI),
 * regardless of local .env configuration. Must be registered after the
 * session exists (its id is in the URL) and before the composer is used.
 */
async function stubInsufficientEvidenceTurn(page: Page, sessionId: string, userText: string) {
  const createdAt = new Date().toISOString();
  await page.route(`**/api/sessions/${sessionId}/messages`, (route) =>
    route.fulfill({
      json: {
        messageId: 999001,
        answer: STUBBED_ANSWER,
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
  await page.route(`**/api/sessions/${sessionId}`, (route) => {
    if (route.request().method() !== "GET") return route.continue();
    route.fulfill({
      json: {
        session: {
          id: Number(sessionId),
          title: "BMW 3 Series Sedan",
          variantId: 23079,
          manufacturerName: "BMW",
          modelName: "BMW 3 Series Sedan",
          variantName: "BMW 3 Series (E90) 320d 6MT RWD (177 HP)",
          createdAt,
          updatedAt: createdAt,
        },
        messages: [
          { id: 999000, role: "user", content: userText, structuredResponseJson: null, createdAt },
          {
            id: 999001,
            role: "assistant",
            content: STUBBED_ANSWER.summary,
            structuredResponseJson: JSON.stringify(STUBBED_ANSWER),
            createdAt,
          },
        ],
      },
    });
  });
}

test.describe("golden path: select vehicle, chat, reload", () => {
  test("select BMW 3 Series (E90) 320d, send a message, and recover it after reload", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByRole("heading", { name: /AI-assisted diagnosis for real cars/i })).toBeVisible();

    await page.getByRole("link", { name: "Start a diagnosis →" }).first().click();
    await expect(page).toHaveURL(/\/chat$/);

    await page.getByRole("button", { name: "Select manufacturer" }).click();
    await page.getByPlaceholder("Type to search, or browse below…").fill("BMW");
    await page.getByRole("option", { name: "BMW", exact: true }).click();

    await page.getByRole("button", { name: "Choose a manufacturer first" }).or(page.getByRole("button", { name: "Select model" })).click();
    await page.getByPlaceholder("Type to search, or browse below…").fill("3 Series Sedan");
    await page.getByRole("option", { name: "BMW 3 Series Sedan" }).click();

    await page.getByLabel("Year").selectOption({ label: "2008" });
    await page.getByLabel("Motorization").selectOption({ label: "BMW 3 Series (E90) 320d 6MT RWD (177 HP)" });

    await page.getByRole("button", { name: "Start a diagnosis →" }).click();
    await expect(page).toHaveURL(/\/chat\/\d+$/);
    const sessionId = page.url().match(/\/chat\/(\d+)$/)![1];
    const conversationHeader = page.getByRole("banner");
    await expect(conversationHeader.getByText("BMW 3 Series Sedan")).toBeVisible();
    await expect(conversationHeader.getByText("BMW 3 Series (E90) 320d 6MT RWD (177 HP)")).toBeVisible();

    const userText = "The driver's side window will not go up anymore.";
    await stubInsufficientEvidenceTurn(page, sessionId, userText);
    const composer = page.getByPlaceholder("Describe the symptom in detail…");
    await composer.fill(userText);
    await composer.press("Enter");

    await expect(page.getByText("The driver's side window will not go up anymore.")).toBeVisible();
    await expect(page.getByText(/insufficient evidence/i)).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText(/OpenAI is not configured/i)).toBeVisible();

    const url = page.url();
    await page.reload();
    await expect(page).toHaveURL(url);
    await expect(page.getByText("The driver's side window will not go up anymore.")).toBeVisible();
    await expect(page.getByText(/OpenAI is not configured/i)).toBeVisible();
  });
});

test.describe("conversation ownership isolation", () => {
  test("a different anonymous visitor cannot open someone else's conversation", async ({ page, browser }) => {
    await page.goto("/chat");
    await page.getByRole("button", { name: "Select manufacturer" }).click();
    await page.getByPlaceholder("Type to search, or browse below…").fill("BMW");
    await page.getByRole("option", { name: "BMW", exact: true }).click();
    await page.getByRole("button", { name: "Select model" }).click();
    await page.getByPlaceholder("Type to search, or browse below…").fill("3 Series Sedan");
    await page.getByRole("option", { name: "BMW 3 Series Sedan" }).click();
    await page.getByLabel("Year").selectOption({ label: "2008" });
    await page.getByLabel("Motorization").selectOption({ label: "BMW 3 Series (E90) 320d 6MT RWD (177 HP)" });
    await page.getByRole("button", { name: "Start a diagnosis →" }).click();
    await expect(page).toHaveURL(/\/chat\/\d+$/);
    const sessionUrl = page.url();

    // A brand-new browser context has no visitor cookie at all.
    const strangerContext = await browser.newContext();
    const strangerPage = await strangerContext.newPage();
    await strangerPage.goto(sessionUrl);
    await expect(strangerPage.getByText(/couldn.t load this conversation/i)).toBeVisible();
    await strangerContext.close();
  });
});

test.describe("mobile layout", () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test("sidebar is hidden behind a menu button and opens without horizontal overflow", async ({ page }) => {
    await page.goto("/chat");

    const menuButton = page.getByRole("button", { name: "Open menu" });
    await expect(menuButton).toBeVisible();

    const bodyWidth = await page.evaluate(() => document.documentElement.scrollWidth);
    expect(bodyWidth).toBeLessThanOrEqual(390);

    await menuButton.click();
    await expect(page.getByRole("link", { name: "+ New chat" })).toBeVisible();
  });
});
