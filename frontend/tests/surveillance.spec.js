import { test, expect } from "@playwright/test";

test("the surveillance desk shows red-team runs and the alert queue", async ({
  page,
}) => {
  await page.goto("/surveillance");
  await expect(
    page.getByRole("heading", { name: "Surveillance", exact: true }),
  ).toBeVisible();
  // a fresh app has no runs; one with runs opens the newest
  await expect(
    page
      .getByRole("heading", {
        name: /No red-team runs yet|Claude|detector|stopped/,
      })
      .first(),
  ).toBeVisible();

  await page.getByRole("button", { name: "Alerts", exact: true }).click();
  await expect(page).toHaveURL(/tab=alerts/);
  await expect(
    page.getByRole("heading", { name: "Alert queue", exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("combobox")).toHaveValue("spoofing-rules");

  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Alert queue", exact: true }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Terminal", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Trading terminal", exact: true }),
  ).toBeVisible();
});

test("the surveillance desk fits a phone", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  for (const tab of ["red-team", "alerts"]) {
    await page.goto("/surveillance?tab=" + tab);
    // the page title hides on a phone; the tabs stay
    await expect(
      page.getByRole("button", { name: "Alerts", exact: true }),
    ).toBeVisible();
    await page.waitForLoadState("networkidle");
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
  }
});
