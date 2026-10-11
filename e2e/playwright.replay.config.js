// Demo bez serwera (scripts/demo): statyczna paczka z przewidywalnym nagraniem. Bez gieldy i bez Dockera.
//   npm run test:replay
import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "tests",
  testMatch: "replay.spec.js",
  timeout: 20_000,
  reporter: "list",
  webServer: { command: "node replay.fixture.mjs && node ../scripts/demo/serve.mjs .replay-site 8092", url: "http://localhost:8092", reuseExistingServer: false },
  use: { baseURL: "http://localhost:8092", channel: process.env.CI ? undefined : process.env.PW_CHANNEL || "chrome", locale: "pl-PL" },
});
