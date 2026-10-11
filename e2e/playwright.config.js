// Testy e2e terminala na dzialajacej gieldzie. Gielda bez botow i od pustego dziennika, zeby kazdy
// przebieg widzial te same arkusze:
//   ./gradlew bootRun --args='--server.port=8090 --exchange.journal.resume=false'
//   cd e2e && npm test
import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "tests",
  testIgnore: "replay.spec.js",
  timeout: 20_000,
  // Testy dziela jedna gielde -- kazdy handluje na innej spolce, ale kolejno, bez wyscigow.
  workers: 1,
  reporter: "list",
  use: {
    baseURL: process.env.BASE_URL || "http://localhost:8090",
    channel: process.env.PW_CHANNEL || "chrome",
    locale: "pl-PL",
  },
});
