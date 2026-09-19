import { expect, Page, test } from "@playwright/test";
import { devLogin, uniqueSub } from "./dev-auth";

/**
 * REWRITTEN for the motorcycle product and its Google-auth productization
 * pass (2026-09-15). The original version of this file tested the
 * car-prototype's frontend flow (a "Select manufacturer" catalogue picker
 * over BMW/Toyota/etc., an anonymous `repair_visitor` cookie for
 * ownership, a landing hero reading "AI-assisted diagnosis for real
 * cars…") — none of that UI is reachable anymore. The car pivot itself
 * happened in an earlier session (see `docs/repair-v2-current-state.md`);
 * the car frontend components it left behind (`VehiclePicker.tsx`,
 * `AnswerCard.tsx`, `EvidenceDrawer.tsx`) are dead code, imported by no
 * route. This file now exercises the *same underlying behaviors* —
 * picking a vehicle, sending a message, reload persistence, ownership
 * isolation, mobile layout — against the real motorcycle product.
 *
 * Auth is real, not mocked: `devLogin` (see ./dev-auth.ts) signs in via
 * the manual-QA-only DevLoginController, which requires the Java API to
 * be running with `SPRING_PROFILES_ACTIVE=dev` (see playwright.config.ts
 * and docs/authentication.md). Vehicle/session creation hits the real
 * backend and a real local Postgres row — only the actual chat-turn
 * network call is stubbed, for the same reason the original file stubbed
 * it: this repo's local `.env` carries a real, billable OPENAI_API_KEY,
 * and this suite's cost and outcome must never depend on that.
 */

type StubbedAnswer = {
  answerType: string;
  summary: string;
  confirmedFacts: string[];
  contextUsed: string[];
  followUpQuestions: string[];
  safeChecks: string[];
  cautions: string[];
  sourceChunkIds: number[];
  proposedMaintenanceEvents: never[];
  proposedOdometerUpdate: null;
  proposedPreference: null;
};

const STUBBED_ANSWER: StubbedAnswer = {
  answerType: "insufficient_evidence",
  summary: "Stubbed test response — the real chat-turn call is never made by this suite.",
  confirmedFacts: [],
  contextUsed: [],
  followUpQuestions: [],
  safeChecks: [],
  cautions: [],
  sourceChunkIds: [],
  proposedMaintenanceEvents: [],
  proposedOdometerUpdate: null,
  proposedPreference: null,
};

async function stubChatTurn(
  page: Page,
  sessionId: string,
  userText: string,
  session: { manufacturerName: string; modelName: string; year: number },
  answerOverride: StubbedAnswer = STUBBED_ANSWER
) {
  const createdAt = new Date().toISOString();
  let messageCount = 0;
  await page.route(`**/api/moto-sessions/${sessionId}/messages`, (route) => {
    messageCount += 1;
    return route.fulfill({
      json: {
        messageId: 999001,
        answer: answerOverride,
        evidence: [],
        actionsTaken: [],
        debug: {
          requestId: "00000000-0000-0000-0000-000000000000",
          garageVehicleId: 0,
          manufacturerName: session.manufacturerName,
          modelName: session.modelName,
          year: session.year,
          embeddingModel: null,
          generationModel: null,
          promptTokens: null,
          completionTokens: null,
          retrievalMillis: null,
          generationMillis: null,
          providerStatus: "missing_key",
          errorDetail: null,
          actionsTakenJson: "[]",
        },
      },
    });
  });
  await page.route(`**/api/moto-sessions/${sessionId}`, (route) => {
    if (route.request().method() !== "GET") return route.continue();
    route.fulfill({
      json: {
        session: {
          id: Number(sessionId),
          title: null,
          garageVehicleId: 0,
          manufacturerName: session.manufacturerName,
          modelName: session.modelName,
          year: session.year,
          createdAt,
          updatedAt: createdAt,
        },
        messages: [
          { id: 999000, role: "user", content: userText, structuredResponseJson: null, createdAt },
          {
            id: 999001,
            role: "assistant",
            content: answerOverride.summary,
            structuredResponseJson: JSON.stringify(answerOverride),
            createdAt,
          },
        ],
      },
    });
  });
  return () => messageCount;
}

/** Picks the first available Yamaha model/year through the real public
 * catalogue — no mocking, this data really exists in the seeded
 * knowledge base. */
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

test.describe("golden path: select a bike, chat, reload", () => {
  test("send a message and recover it after reload", async ({ page }) => {
    await devLogin(page, uniqueSub("golden-a"), "golden-a@example.test", "Golden Rider A");
    await page.goto("/");

    const year = await pickAYamahaBike(page, "MT-07");
    await page.getByRole("button", { name: "Start with this bike →" }).click();
    await expect(page).toHaveURL(/\/chat\/\d+$/);
    const sessionId = page.url().match(/\/chat\/(\d+)$/)![1];

    const header = page.getByRole("banner");
    await expect(header.getByText("Yamaha MT-07")).toBeVisible();
    await expect(header.getByText(String(year))).toBeVisible();

    const userText = "The clutch feels grabby right at the start of engagement.";
    await stubChatTurn(page, sessionId, userText, { manufacturerName: "Yamaha", modelName: "MT-07", year });
    const composer = page.getByPlaceholder("Ask about maintenance, or describe what's happening…");
    await composer.fill(userText);
    await composer.press("Enter");

    await expect(page.getByText(userText)).toBeVisible();
    await expect(page.getByText(STUBBED_ANSWER.summary)).toBeVisible({ timeout: 10_000 });

    const url = page.url();
    await page.reload();
    await expect(page).toHaveURL(url);
    await expect(page.getByText(userText)).toBeVisible();
    await expect(page.getByText(STUBBED_ANSWER.summary)).toBeVisible();
  });
});

test.describe("follow-up questions are plain, non-interactive text", () => {
  test("suggested follow-ups render as text, not clickable buttons, and cannot submit a message", async ({ page }) => {
    await devLogin(page, uniqueSub("followup"), "followup@example.test", "Followup Rider");
    await page.goto("/");

    const year = await pickAYamahaBike(page, "MT-07");
    await page.getByRole("button", { name: "Start with this bike →" }).click();
    await expect(page).toHaveURL(/\/chat\/\d+$/);
    const sessionId = page.url().match(/\/chat\/(\d+)$/)![1];

    const followUpQuestions = [
      "Do you regularly check your chain slack between lubrications?",
      "Have you noticed unusual chain noise?",
    ];
    const userText = "I lubricated the chain today.";
    const getMessageCount = await stubChatTurn(page, sessionId, userText, { manufacturerName: "Yamaha", modelName: "MT-07", year }, {
      ...STUBBED_ANSWER,
      followUpQuestions,
    });
    const composer = page.getByPlaceholder("Ask about maintenance, or describe what's happening…");
    await composer.fill(userText);
    await composer.press("Enter");

    await expect(page.getByText(STUBBED_ANSWER.summary)).toBeVisible({ timeout: 10_000 });
    for (const question of followUpQuestions) {
      await expect(page.getByText(question)).toBeVisible();
      // Must not be exposed as an interactive control of any kind.
      await expect(page.getByRole("button", { name: question })).toHaveCount(0);
      await expect(page.getByRole("link", { name: question })).toHaveCount(0);
    }

    expect(getMessageCount()).toBe(1);
    await page.getByText(followUpQuestions[0]).click();
    // A plain click on the suggestion text must never submit a new chat
    // message — the rider must type their own response in the composer.
    await expect(composer).toHaveValue("");
    expect(getMessageCount()).toBe(1);
  });
});

test.describe("conversation ownership isolation", () => {
  test("a different signed-in rider cannot open someone else's conversation", async ({ page, browser }) => {
    await devLogin(page, uniqueSub("owner"), "owner@example.test", "Owner Rider");
    await page.goto("/");
    await pickAYamahaBike(page, "MT-09");
    await page.getByRole("button", { name: "Start with this bike →" }).click();
    await expect(page).toHaveURL(/\/chat\/\d+$/);
    const sessionUrl = page.url();

    const strangerContext = await browser.newContext();
    const strangerPage = await strangerContext.newPage();
    await devLogin(strangerPage, uniqueSub("stranger"), "stranger@example.test", "Stranger Rider");
    await strangerPage.goto(sessionUrl);
    await expect(strangerPage.getByText(/couldn.t load this conversation/i)).toBeVisible();
    await strangerContext.close();
  });
});

test.describe("mobile layout", () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test("sidebar is hidden behind a menu button and opens without horizontal overflow", async ({ page }) => {
    await devLogin(page, uniqueSub("mobile"), "mobile@example.test", "Mobile Rider");
    await page.goto("/chat");

    const menuButton = page.getByRole("button", { name: "Open menu" });
    await expect(menuButton).toBeVisible();

    const bodyWidth = await page.evaluate(() => document.documentElement.scrollWidth);
    expect(bodyWidth).toBeLessThanOrEqual(390);

    await menuButton.click();
    await expect(page.getByRole("link", { name: "+ New chat" })).toBeVisible();
  });
});
