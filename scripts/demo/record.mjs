// Nagrywa prawdziwa sesje gieldy do pliku, z ktorego terminal odtwarza demo bez serwera (js/replay.js).
// Gielda musi dzialac z botami:
//   ./gradlew bootRun --args='--server.port=8090 --spring.profiles.active=demo'
//   node scripts/demo/record.mjs [adres=http://localhost:8090] [minuty=8] [plik=build/demo/recording.json]
// Swiece sa pobierane na starcie: przez pierwsze minuty gieldy wykres bylby pusty, wiec boty powinny
// pohandlowac co najmniej tyle, ile obejmuje wykres (240 swiec 5-sekundowych = 20 min).
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

const [base = "http://localhost:8090", minutes = "8", out = "build/demo/recording.json"] = process.argv.slice(2);

// Te same zapytania co terminal: wykres (5 s, 15 s, 1 min) i mini-wykresy kafelkow (30 s).
const CANDLES = [[5, 240], [15, 240], [60, 240], [30, 60]];
// Obrazy arkusza najwyzej co BOOK_MS na spolke: boty zmieniaja arkusz ~5 razy/s, a obrazy to 80% pliku.
// Transakcje zawsze, wszystkie.
const BOOK_MS = 300;

const get = async (path) => {
  const res = await fetch(base + path);
  if (!res.ok) throw new Error(`${path}: HTTP ${res.status}`);
  return res.json();
};

const instruments = await get("/api/instruments");

// Najpierw swiece, potem strumien: transakcja z tych kilku milisekund przepada, ale zadna nie liczy sie
// dwa razy (w swiecach i w strumieniu).
const candles = {};
for (const symbol of instruments) {
  candles[symbol] = {};
  for (const [seconds, count] of CANDLES) {
    candles[symbol][seconds] = await get(`/api/instruments/${symbol}/candles?seconds=${seconds}&count=${count}`);
  }
}

const messages = [];
const lastBook = new Map();
const socket = new WebSocket(`${base.replace(/^http/, "ws")}/ws/market?symbols=${instruments.join(",")}`);
const startedAt = Date.now();
socket.onmessage = (e) => {
  const at = Date.now() - startedAt, msg = JSON.parse(e.data);
  if (msg.type === "book") {
    if (at - (lastBook.get(msg.symbol) ?? -BOOK_MS) < BOOK_MS) return;
    lastBook.set(msg.symbol, at);
  }
  messages.push([at, msg]);
};
await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });

const durationMs = Number(minutes) * 60_000;
const progress = setInterval(() => process.stdout.write(`\r${messages.length} wiadomosci`), 5000);
await new Promise((resolve) => setTimeout(resolve, durationMs));
clearInterval(progress);
socket.close();

mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, JSON.stringify({ version: 1, startedAt, durationMs, instruments, candles, messages }));
console.log(`\n${out}: ${messages.length} wiadomosci, ${instruments.length} spolek`);
