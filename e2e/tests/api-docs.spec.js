// Swagger UI na dzialajacej gieldzie: opis API z kontrolerow i wywolanie endpointu z przegladarki.
import { expect, test } from "@playwright/test";

test("Swagger UI opisuje API i pozwala je wywolac", async ({ page }) => {
  await page.goto("/swagger-ui.html");
  await expect(page.locator(".info .title")).toContainText("Parkiet");
  await expect(page.locator(".opblock-post .opblock-summary-path").first()).toContainText("/api/instruments/{symbol}/orders");

  const list = page.locator(".opblock-get", { has: page.locator('[data-path="/api/instruments"]') });
  await list.locator(".opblock-summary").click();
  await list.getByRole("button", { name: "Try it out" }).click();
  await list.getByRole("button", { name: "Execute" }).click();

  await expect(list.locator(".live-responses-table tbody .response-col_status")).toHaveText("200");
  await expect(list.locator(".live-responses-table tbody .response-col_description")).toContainText("CDR");
});
