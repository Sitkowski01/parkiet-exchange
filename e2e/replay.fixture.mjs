// Przewidywalne nagranie do testu demo (webServer w playwright.replay.config.js): co 0,5 s transakcja po
// 101,00, co 2 s ktos sprzedaje po 99,00
// (trafia w czekajace zlecenia kupna od 99,00 w gore). Minuta nagrania, wiec test zdazy niezaleznie od tego,
// w ktorym miejscu minuty zacznie sie odtwarzanie.
import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";

const SITE = ".replay-site";

const startedAt = Date.now(), messages = [];
const book = (symbol, sequence) => ({ type: "book", symbol, sequence,
  bids: [{ price: "100.00", quantity: 10, orders: 1 }], asks: [{ price: "101.00", quantity: 10, orders: 1 }] });
const trade = (sequence, at, price, quantity, takerSide) => ({ type: "trade", symbol: "CDR", sequence,
  timestamp: new Date(startedAt + at).toISOString(), price, quantity, takerSide, makerOrderId: 7, takerOrderId: 8 });
let seq = 0;
messages.push([0, book("CDR", ++seq)], [0, book("PKO", 1)]);
for (let at = 500; at < 60_000; at += 500) {
  messages.push(at % 2000 === 0 ? [at, trade(++seq, at, "99.00", 5, "SELL")] : [at, trade(++seq, at, "101.00", 1, "BUY")]);
  messages.push([at, book("CDR", seq)]);
}
const candles = { 5: [], 15: [], 30: [], 60: [] };
mkdirSync("../build/demo", { recursive: true });
writeFileSync("../build/demo/e2e-recording.json", JSON.stringify({ version: 1, startedAt, durationMs: 60_000,
  instruments: ["CDR", "PKO"], candles: { CDR: candles, PKO: candles }, messages }));
execFileSync("node", ["../scripts/demo/build.mjs", "../build/demo/e2e-recording.json", SITE]);

