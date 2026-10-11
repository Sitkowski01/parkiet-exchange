// Terminal "Parkiet" w prawdziwej przegladarce na dzialajacej gieldzie: REST, WebSocket, arkusz i
// "moje zlecenia". Kazdy test ma swoja spolke, bo gielda jest jedna na caly przebieg, i sprzata po sobie
// arkusz, zeby drugi przebieg na tej samej gieldzie widzial to samo.
import { expect, test } from "@playwright/test";

/** Zlecenie "innego klienta" -- prosto do API, z pominieciem przegladarki. */
async function order(request, symbol, body) {
  const res = await request.post(`/api/instruments/${symbol}/orders`, { data: body });
  expect(res.status(), await res.text()).toBe(201);
  return res.json();
}

async function open(page, symbol) {
  await page.goto(`/#${symbol}`);
  await expect(page.locator("#status")).toHaveClass(/live/);
  await expect(page.locator("#inst-symbol")).toHaveText(symbol);
}

async function placeLimit(page, side, price, quantity) {
  await page.locator(`#ticket [data-side="${side}"]`).click();
  await page.locator('#ticket [data-type="LIMIT"]').click();
  await page.locator("#price").fill(price);
  await page.locator("#quantity").fill(String(quantity));
  await page.locator("#submit").click();
}

const myOrder = (page, id) => page.locator("#orders tr", { has: page.locator("td", { hasText: new RegExp(`^${id}$`) }) });

test("laczy sie ze strumieniem i pokazuje wszystkie spolki", async ({ page }) => {
  await open(page, "CDR");
  await expect(page.locator("#strip .tile")).toHaveCount(6);
  await expect(page.locator("#strip .tile.on .tile-sym")).toHaveText("CDR");
  await expect(page.locator("#status .status-text")).toHaveText("na żywo");
});

test("transakcja innego klienta trafia na tasme, do arkusza i na wykres", async ({ page, request }) => {
  await open(page, "PZU");
  await order(request, "PZU", { side: "SELL", type: "LIMIT", price: "50.00", quantity: 5 });
  await order(request, "PZU", { side: "BUY", type: "MARKET", quantity: 2 });

  await expect(page.locator("#tape li").first()).toContainText("50,00");
  await expect(page.locator("#tape li").first()).toContainText("2");
  await expect(page.locator("#asks .book-row")).toHaveCount(1);
  await expect(page.locator("#asks .book-row")).toContainText("3");
  await expect(page.locator("#chart-empty")).toBeHidden();
  await expect(page.locator("#inst-last")).toHaveText("50,00");
  await order(request, "PZU", { side: "BUY", type: "MARKET", quantity: 3 }); // pusty arkusz dla nastepnego przebiegu
});

test("zlecenie z limitem czeka w arkuszu, anulowanie je zdejmuje", async ({ page }) => {
  await open(page, "KGH");
  await placeLimit(page, "BUY", "300", 7);

  await expect(page.locator("#toast")).toContainText("czeka w arkuszu");
  const row = page.locator("#orders tr").first();
  await expect(row.locator(".state")).toHaveText("w arkuszu");
  await expect(page.locator("#bids .book-row.mine")).toContainText("300,00");
  await expect(page.locator("#orders-count")).toHaveText("1");

  await row.locator("button.cancel").click();
  await expect(row.locator(".state")).toHaveText("anulowane");
  await expect(page.locator("#bids .book-row")).toHaveCount(0);
  await expect(page.locator("#orders-count")).toHaveText("0");
});

test("moje zlecenie zrealizowane przez kogos innego zmienia stan", async ({ page, request }) => {
  await open(page, "PKN");
  await placeLimit(page, "BUY", "100,00", 10);
  await expect(page.locator("#orders tr .state")).toHaveText("w arkuszu");
  const id = await page.locator("#orders tr td").first().textContent();

  await order(request, "PKN", { side: "SELL", type: "LIMIT", price: "100.00", quantity: 4 });
  await expect(myOrder(page, id).locator(".state")).toHaveText("częściowo");
  await expect(myOrder(page, id)).toContainText("6 z 10");

  await order(request, "PKN", { side: "SELL", type: "MARKET", quantity: 6 });
  await expect(myOrder(page, id).locator(".state")).toHaveText("zrealizowane");
  await expect(myOrder(page, id).locator("button.cancel")).toHaveCount(0);
  await expect(page.locator("#orders-count")).toHaveText("0");
});

test("PKC kupuje z arkusza po cenie czekajacego zlecenia", async ({ page, request }) => {
  await order(request, "CDR", { side: "SELL", type: "LIMIT", price: "263.00", quantity: 20 });
  await open(page, "CDR");
  await page.locator('#ticket [data-type="MARKET"]').click();
  await expect(page.locator("#price")).toBeDisabled();
  await page.locator("#quantity").fill("5");
  await page.locator("#submit").click();

  await expect(page.locator("#toast")).toContainText("zrealizowano 5 szt.");
  await expect(page.locator("#orders tr .state").first()).toHaveText("zrealizowane");
  await expect(page.locator("#asks .book-row").last()).toContainText("15");
  await order(request, "CDR", { side: "BUY", type: "MARKET", quantity: 15 });
});

test("PKC bez ofert po drugiej stronie przepada", async ({ page }) => {
  await open(page, "ALE");
  await page.locator('#ticket [data-type="MARKET"]').click();
  await page.locator("#quantity").fill("3");
  await page.locator("#submit").click();

  await expect(page.locator("#toast")).toContainText("brak ofert");
  await expect(page.locator("#orders tr .state").first()).toHaveText("nieaktywne");
});

test("zle dane w formularzu nie wychodza do serwera", async ({ page }) => {
  await open(page, "PKO");
  const posts = [];
  page.on("request", (r) => r.method() === "POST" && posts.push(r.url()));

  await placeLimit(page, "BUY", "10", 0);
  await expect(page.locator("#ticket-note")).toContainText("Ilość");
  await placeLimit(page, "BUY", "10", 1.5);
  await expect(page.locator("#ticket-note")).toContainText("Ilość");
  await placeLimit(page, "BUY", "abc", 1);
  await expect(page.locator("#ticket-note")).toContainText("Cena");
  await placeLimit(page, "BUY", "10,001", 1);
  await expect(page.locator("#ticket-note")).toContainText("Cena");
  await placeLimit(page, "BUY", "10000000,01", 1);
  await expect(page.locator("#ticket-note")).toContainText("10 000 000");

  expect(posts).toEqual([]);
  await expect(page.locator("#orders tr")).toHaveCount(0);

  await placeLimit(page, "BUY", "10", 1);
  await expect(page.locator("#ticket-note")).toHaveText("");
  await expect(page.locator("#orders tr")).toHaveCount(1);
});

for (const viewport of [{ width: 1280, height: 720 }, { width: 390, height: 844 }]) {
  test(`uklad ${viewport.width}x${viewport.height}: bez poziomego paska, stopka pod panelami`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await open(page, "CDR");
    const layout = await page.evaluate(() => ({
      overflow: document.documentElement.scrollWidth - window.innerWidth,
      logBottom: document.querySelector(".log-panel").getBoundingClientRect().bottom,
      footTop: document.querySelector(".foot").getBoundingClientRect().top,
    }));
    expect(layout.overflow).toBeLessThanOrEqual(0);
    expect(layout.footTop).toBeGreaterThanOrEqual(layout.logBottom);
  });
}
