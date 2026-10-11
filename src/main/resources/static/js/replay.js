// Demo bez serwera: terminal odtwarza nagranie prawdziwej sesji (scripts/demo/record.mjs) w petli,
// z czasem przesunietym na "teraz". Zlecenia z formularza sa symulowane tutaj, na odtwarzanym arkuszu:
// PKC i limit bija w najlepsze oferty, reszta limitu czeka, az nagrana transakcja albo arkusz dojdzie do jej ceny.
// Ten sam interfejs co market.js (api, candles, stream) -- reszta terminala nie wie, ze serwera nie ma.
import { applyTrade } from "./chart.js";
import { toApi, toGrosze } from "./format.js";
import { candle, dispatch } from "./market.js";

// Czas przesuwany o cale minuty: swiece 5/15/30/60 s zostaja na swoich granicach.
const MINUTE = 60_000;
const TICK_MS = 50;

let rec, period, shift, loopStart, cursor = 0, seqOffset = 0, maxSeq = 0;
const series = new Map(); // "CDR:5" -> swiece, dopisywane odtwarzanymi transakcjami
const books = new Map(); // ostatni odtworzony arkusz spolki, ceny w groszach
const resting = []; // symulowane zlecenia czekajace: { id, symbol, side, price, remaining }
let nextId = 1, out;

// Nagranie wczytywane przy pierwszym uzyciu: transport.js laduje ten modul takze w trybie z serwerem.
let loading;
const ready = () => (loading ??= fetch(document.documentElement.dataset.replay).then((r) => r.json()).then(init));

function init(r) {
  rec = r;
  period = Math.ceil(r.durationMs / MINUTE) * MINUTE;
  shift = Math.floor((Date.now() - r.startedAt) / MINUTE) * MINUTE;
  for (const s of r.instruments) {
    for (const [seconds, list] of Object.entries(r.candles[s])) {
      series.set(`${s}:${seconds}`, list.map((c) => ({ ...candle(c), t: Date.parse(c.time) + shift })));
    }
  }
  for (const [, m] of r.messages) maxSeq = Math.max(maxSeq, m.sequence);
}

function error(status, text) {
  const err = new Error(text);
  err.status = status;
  return err;
}

export async function api(path, options = {}) {
  await ready();
  const m = path.match(/^\/api\/instruments(?:\/(\w+)\/orders(?:\/(\d+))?)?$/);
  if (!m) throw error(404, "Brak w nagraniu");
  if (!m[1]) return [...rec.instruments];
  if (options.method === "POST") return place(m[1], JSON.parse(options.body));
  if (options.method === "DELETE") return cancel(Number(m[2]));
  throw error(404, "Brak w nagraniu");
}

export async function candles(symbol, seconds, count) {
  await ready();
  return (series.get(`${symbol}:${seconds}`) || []).slice(-count).map((c) => ({ ...c }));
}

export function stream(symbols, { onBook, onTrade, onStatus }) {
  const lastBook = new Map();
  out = { lastBook, onBook, onTrade };
  ready().then(() => {
    onStatus("replay");
    // Start w polowie minuty nagrania: to, co bylo wczesniej, tylko odswieza stan, bez wysylania.
    const offset = Date.now() - rec.startedAt - shift;
    loopStart = Date.now() - offset;
    while (cursor < rec.messages.length && rec.messages[cursor][0] < offset) absorb(rec.messages[cursor++][1]);
    for (const s of rec.instruments) if (books.has(s)) sendBook(s);
    setInterval(tick, TICK_MS);
  });
  return () => {};
}

function tick() {
  const now = Date.now() - loopStart;
  while (cursor < rec.messages.length && rec.messages[cursor][0] <= now) {
    const msg = rec.messages[cursor++][1];
    absorb(msg);
    if (msg.type === "book") sendBook(msg.symbol);
    else send(moved(msg));
  }
  if (cursor === rec.messages.length && now >= period) {
    // Petla: czas i numeracja ida dalej, zeby swiece i zszywanie po sequence nie widzialy cofniecia.
    cursor = 0; loopStart += period; shift += period; seqOffset += maxSeq;
  }
}

/** Nagrana wiadomosc zmienia stan demo: arkusz spolki albo swiece i moje czekajace zlecenia. */
function absorb(msg) {
  if (msg.type === "book") {
    const level = (l) => ({ price: toGrosze(l.price), quantity: l.quantity, orders: l.orders });
    books.set(msg.symbol, { sequence: msg.sequence, bids: msg.bids.map(level), asks: msg.asks.map(level) });
    return;
  }
  const t = Date.parse(msg.timestamp) + shift, p = toGrosze(msg.price);
  for (const s of [5, 15, 30, 60]) {
    const list = series.get(`${msg.symbol}:${s}`);
    if (list) applyTrade(list, s, t, p, msg.quantity);
  }
  if (out) for (const o of resting.filter((o) => o.symbol === msg.symbol && crosses(o, p))) fillResting(o, msg.quantity, msg);
}

const crosses = (o, p) => (o.side === "BUY" ? p <= o.price : p >= o.price);

function moved(msg) {
  // Numery zlecen z nagrania sa obce: zerujemy je, zeby nie wziac ich za moje symulowane zlecenia (od 1).
  return { ...msg, sequence: msg.sequence + seqOffset, timestamp: new Date(Date.parse(msg.timestamp) + shift).toISOString(),
    makerOrderId: 0, takerOrderId: 0 };
}

function send(msg) {
  dispatch(msg, out.lastBook, out);
}

/** Obraz arkusza z moimi czekajacymi zleceniami; te, ktore arkusz juz przeskoczyl, realizuja sie. */
function sendBook(symbol) {
  const book = books.get(symbol);
  for (const o of resting.filter((o) => o.symbol === symbol)) {
    const best = (o.side === "BUY" ? book.asks : book.bids)[0];
    if (best && crosses(o, best.price)) fillResting(o, best.quantity, { symbol, sequence: book.sequence, timestamp: null });
  }
  const level = (l) => ({ price: toApi(l.price), quantity: l.quantity, orders: l.orders });
  const merge = (levels, side) => {
    const all = levels.map((l) => ({ ...l }));
    for (const o of resting.filter((o) => o.symbol === symbol && o.side === side)) {
      const same = all.find((l) => l.price === o.price);
      if (same) { same.quantity += o.remaining; same.orders += 1; } else all.push({ price: o.price, quantity: o.remaining, orders: 1 });
    }
    return all.sort((a, b) => (side === "BUY" ? b.price - a.price : a.price - b.price)).map(level);
  };
  send({ type: "book", symbol, sequence: book.sequence + seqOffset, bids: merge(book.bids, "BUY"), asks: merge(book.asks, "SELL") });
}

/** Moje czekajace zlecenie trafione przez kogos z nagrania: transakcja po mojej cenie (jestem makerem). */
function fillResting(o, quantity, at) {
  const q = Math.min(o.remaining, quantity);
  o.remaining -= q;
  if (!o.remaining) resting.splice(resting.indexOf(o), 1);
  send({
    type: "trade", symbol: o.symbol, sequence: at.sequence + seqOffset,
    timestamp: at.timestamp ? new Date(Date.parse(at.timestamp) + shift).toISOString() : new Date().toISOString(),
    price: toApi(o.price), quantity: q, takerSide: o.side === "BUY" ? "SELL" : "BUY", makerOrderId: o.id, takerOrderId: 0,
  });
}

/** Zlecenie z formularza: bierze z najlepszych ofert po drugiej stronie, reszta limitu czeka. */
function place(symbol, { side, type, price, quantity }) {
  if (!rec.instruments.includes(symbol)) throw error(404, `Nieznana spółka ${symbol}`);
  const id = nextId++, limit = type === "LIMIT" ? toGrosze(price) : null;
  const events = [];
  let left = quantity;
  for (const l of (side === "BUY" ? books.get(symbol)?.asks : books.get(symbol)?.bids) || []) {
    if (!left || (limit != null && !crosses({ side, price: limit }, l.price))) break;
    const q = Math.min(left, l.quantity);
    left -= q;
    events.push({ type: "TRADE", makerOrderId: 0, takerOrderId: id, takerSide: side, price: toApi(l.price), quantity: q });
    send({ type: "trade", symbol, sequence: (books.get(symbol)?.sequence ?? 0) + seqOffset, timestamp: new Date().toISOString(),
      price: toApi(l.price), quantity: q, takerSide: side, makerOrderId: 0, takerOrderId: id });
  }
  if (left && limit != null) {
    resting.push({ id, symbol, side, price: limit, remaining: left });
    events.push({ type: "RESTED", orderId: id, side, price: toApi(limit), quantity: left });
    sendBook(symbol);
  } else if (left) {
    events.push({ type: "CANCELLED", orderId: id, quantity: left, reason: "NO_LIQUIDITY" });
  }
  return { orderId: id, events };
}

function cancel(id) {
  const o = resting.find((r) => r.id === id);
  if (!o) throw error(404, "Zlecenia nie ma w arkuszu");
  resting.splice(resting.indexOf(o), 1);
  if (books.has(o.symbol)) sendBook(o.symbol);
  return { type: "CANCELLED", orderId: o.id, quantity: o.remaining, reason: "REQUESTED" };
}
