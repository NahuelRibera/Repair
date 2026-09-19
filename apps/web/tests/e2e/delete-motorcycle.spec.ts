import { expect, Page, test } from "@playwright/test";
import { devLogin, uniqueSub } from "./dev-auth";

/**
 * End-to-end coverage for the "delete motorcycle" flow in My Garage: the
 * × button, its confirmation dialog (Cancel vs. confirm), the card and
 * its associated sidebar conversation disappearing, persistence of the
 * deletion across a reload, and the empty-garage state when the last
 * bike is removed.
 */

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

test.describe("delete a motorcycle from My Garage", () => {
  test("cancel keeps the bike, confirming removes it and its sidebar conversations", async ({ page }) => {
    await devLogin(page, uniqueSub("delete-bike"), "delete-bike@example.test", "Delete Bike Rider");
    await page.goto("/");

    await pickAYamahaBike(page, "Ténéré 700 World Raid");
    await page.getByRole("button", { name: "Start with this bike →" }).click();
    await expect(page).toHaveURL(/\/chat\/\d+$/);

    // A real chat session now exists for this bike — its sidebar entry
    // must disappear once the bike is deleted (Part B requirement #21).
    const sidebar = page.locator("aside");
    await expect(sidebar.getByText("Ténéré 700 World Raid")).toBeVisible();

    await page.goto("/garage");
    const deleteButton = page.getByRole("button", { name: /Delete Yamaha Ténéré 700 World Raid/ });
    await expect(deleteButton).toBeVisible();

    // 1. Cancel must leave everything untouched.
    await deleteButton.click();
    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText("Delete this motorcycle?")).toBeVisible();
    await expect(
        dialog.getByText("This will permanently remove the motorcycle and its maintenance history, saved preferences and associated conversations.")
    ).toBeVisible();
    await dialog.getByRole("button", { name: "Cancel" }).click();
    await expect(dialog).toBeHidden();
    await expect(deleteButton).toBeVisible();

    // 2. Escape must also close the dialog without deleting.
    await deleteButton.click();
    await expect(page.getByRole("alertdialog")).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.getByRole("alertdialog")).toBeHidden();
    await expect(deleteButton).toBeVisible();

    // 3. Confirming actually deletes it.
    await deleteButton.click();
    await page.getByRole("alertdialog").getByRole("button", { name: "Delete motorcycle" }).click();
    await expect(page.getByRole("alertdialog")).toBeHidden();
    await expect(deleteButton).toBeHidden();

    // The sidebar's conversation for this now-deleted bike must be gone
    // too, without needing a full page reload.
    await expect(sidebar.getByText("Ténéré 700 World Raid")).toBeHidden();

    // 4. Persists across reload.
    await page.reload();
    await expect(deleteButton).toBeHidden();
    await expect(sidebar.getByText("Ténéré 700 World Raid")).toBeHidden();

    // 5. This was the rider's only bike — empty-garage state shows.
    await expect(page.getByText("You haven't added a motorcycle yet.")).toBeVisible();
  });
});
