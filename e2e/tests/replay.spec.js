// Demo z nagrania (js/replay.js): terminal bez serwera, zlecenia symulowane na odtwarzanym arkuszu.
// Nagranie z replay.setup.js: arkusz CDR 100,00 / 101,00, co 2 s ktos sprzedaje 5 szt. po 99,00.
import { expect, test } from "@playwright/test";

async function open(page) {
  await page.goto("/#CDR");
  await expect(page.locator("#status .status-text")).toHaveText("demo · nagranie sesji");
}

async function placeLimit(page, side, price, quantity) {
  await page.locator(`#ticket [data-side="${side}"]`).click();
  await page.locator('#ticket [data-type="LIMIT"]').click();
  await page.locator("#price").fill(price);
  await page.locator("#quantity").fill(String(quantity));
  await page.locator("#submit").click();
}

test("odtwarza nagranie bez zadnego serwera gieldy", async ({ page }) => {
  const server = [];
  page.on("websocket", (ws) => server.push(ws.url()));
  page.on("request", (r) => r.url().includes("/api/") && server.push(r.url()));
  await open(page);

  await expect(page.locator("#replay-note")).toBeVisible();
  await expect(page.locator("#strip .tile")).toHaveCount(2);
  await expect(page.locator("#tape li").first()).toBeVisible();
  await expect(page.locator("#asks .book-row")).toContainText("101,00");
  await expect(page.locator("#bids .book-row")).toContainText("100,00");
  expect(server).toEqual([]);
});

test("PKC realizuje sie na odtwarzanym arkuszu", async ({ page }) => {
  await open(page);
  await page.locator('#ticket [data-type="MARKET"]').click();
  await page.locator("#quantity").fill("3");
  await page.locator("#submit").click();

  await expect(page.locator("#toast")).toContainText("#1: zrealizowano 3 szt. po śr. 101,00 zł");
  await expect(page.locator("#orders tr .state")).toHaveText("zrealizowane");
});

test("limit czeka w arkuszu i realizuje sie, gdy nagrana transakcja dojdzie do jego ceny", async ({ page }) => {
  await open(page);
  await placeLimit(page, "BUY", "99,50", 8);

  await expect(page.locator("#orders tr .state")).toHaveText("w arkuszu");
  await expect(page.locator("#bids .book-row.mine")).toContainText("99,50");
  await expect(page.locator("#orders tr .state")).toHaveText("częściowo", { timeout: 5000 });
  await expect(page.locator("#orders tr")).toContainText("3 z 8");
  await expect(page.locator("#orders tr .state")).toHaveText("zrealizowane", { timeout: 5000 });
  await expect(page.locator("#bids .book-row.mine")).toHaveCount(0);
});

test("anulowanie zdejmuje symulowane zlecenie z arkusza", async ({ page }) => {
  await open(page);
  await placeLimit(page, "BUY", "90", 2);
  await expect(page.locator("#bids .book-row.mine")).toContainText("90,00");

  await page.locator("#orders button.cancel").click();
  await expect(page.locator("#orders tr .state")).toHaveText("anulowane");
  await expect(page.locator("#bids .book-row.mine")).toHaveCount(0);
});
