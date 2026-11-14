import { test, expect } from "@playwright/test";

test("home page introduces the product and launches the working terminal", async ({
  page,
  request,
}) => {
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: /Paper trading on a real order book/ }),
  ).toBeVisible();
  await page
    .getByRole("link", { name: "Explore the terminal", exact: true })
    .click();
  await expect(page).toHaveURL(/\/terminal$/);
  await expect(
    page.getByRole("heading", { name: "Trading terminal", exact: true }),
  ).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Trading terminal", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("link", { name: "Paper Trader home", exact: true })
    .click();
  await expect(page).toHaveURL(/\/$/);
  await expect(
    page.getByRole("heading", { name: /Paper trading on a real order book/ }),
  ).toBeVisible();
  const trailingSlash = await request.get("/terminal/");
  expect(trailingSlash.status()).toBe(200);
  const missingApi = await request.get("/api/does-not-exist");
  expect(missingApi.status()).toBe(404);
});

test("instrument previews open the corresponding chart", async ({ page }) => {
  await page.goto("/");
  await page.locator('.home-quote[href="/terminal?symbol=NVDA"]').click();
  await expect(
    page.getByRole("heading", { name: "NVDA NVIDIA", exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel("Instrument", { exact: true })).toHaveValue(
    "NVDA",
  );
});

test("home sections, FAQ, and mobile navigation are usable", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  const menu = page.getByRole("button", { name: "Toggle navigation" });
  await menu.click();
  await expect(menu).toHaveAttribute("aria-expanded", "true");
  await page
    .getByRole("navigation", { name: "Main navigation" })
    .getByRole("link", { name: "FAQ", exact: true })
    .click();
  await expect(menu).toHaveAttribute("aria-expanded", "false");
  await page.getByText("Am I trading with real money?").click();
  await expect(
    page.getByText(/No\. Every account starts with virtual cash/),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page
    .getByRole("link", { name: "Explore the terminal", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Trading terminal", exact: true }),
  ).toBeVisible();
});

test("home layout renders at desktop and mobile widths", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/");
  await expect(
    page.locator(".broker-market-table tbody tr").first(),
  ).toContainText("$");
  // the demo's order book shows the house quote from the running engine
  await expect(
    page.getByLabel("AAPL order book").getByText("house").first(),
  ).toBeVisible();
  await page.screenshot({
    path: "test-results/home-desktop.png",
    fullPage: false,
  });
  await page.screenshot({ path: "test-results/home-full.png", fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "test-results/home-mobile.png",
    fullPage: true,
  });
  await page.screenshot({
    path: "test-results/home-mobile-hero.png",
    fullPage: false,
  });
});

test("home account-opening flow creates an account and survives refresh", async ({
  page,
}) => {
  await page.goto("/");
  await page
    .getByRole("link", { name: "Open practice account", exact: true })
    .first()
    .click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.getByLabel("New account name").fill("broker_" + Date.now());
  await page
    .getByRole("button", { name: "Create account", exact: true })
    .click();
  await expect(page.getByRole("dialog")).not.toBeVisible();
  await expect(page.getByText(/Account #/)).toBeVisible();
  await expect(page).toHaveURL(/\/terminal$/);
  await page.reload();
  await expect(page.getByRole("dialog")).not.toBeVisible();
  await expect(page.getByText(/Account #/)).toBeVisible();
});

test("the home page demo places a real order and shows what it did", async ({
  page,
}) => {
  await page.goto("/");
  const demo = page.getByRole("region", { name: "Live demo" });
  await demo.getByRole("button", { name: "MSFT", exact: true }).click();
  await expect(demo.getByLabel("MSFT order book")).toContainText("house");
  await demo.getByLabel("Shares").fill("2");
  await demo.getByRole("button", { name: "Buy 2 MSFT", exact: true }).click();

  const receipt = demo.getByRole("list", { name: "What your order did" });
  await expect(receipt).toContainText("Placed");
  await expect(receipt).toContainText("Filled");
  await expect(receipt).toContainText("buy 2 MSFT @");
  await expect(demo).toContainText(/Account #\d+ · \$[\d,.]+ cash · 2 MSFT/);

  // the same order is on the public record, and the books still balance
  await expect(
    page.getByRole("list", { name: "Latest order events" }),
  ).toContainText("buy 2 MSFT @");
  await expect(page.locator(".facts dd").first()).toHaveText("Balanced");

  // the guest account carries over to the terminal
  await demo.getByRole("link", { name: /See it in the terminal/ }).click();
  await expect(page).toHaveURL(/\/terminal$/);
  await expect(page.getByText(/Account #/)).toBeVisible();
  await expect(
    page
      .locator("section.positions")
      .getByRole("button", { name: "MSFT", exact: true }),
  ).toBeVisible();
});

test("a refused demo order shows the reason", async ({ page }) => {
  await page.goto("/");
  const demo = page.getByRole("region", { name: "Live demo" });
  await demo.getByRole("button", { name: "Sell", exact: true }).click();
  await demo.getByRole("button", { name: "Sell 5 AAPL", exact: true }).click();
  await expect(demo.getByRole("alert")).toContainText("insufficient shares");
});
