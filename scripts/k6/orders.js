// Test obciazeniowy HTTP: zlecenia LIMIT i PKC na wszystkie spolki naraz, czesc anulowana.
// docker run --rm -i -e BASE=http://host.docker.internal:8080 -e RATE=1000 grafana/k6 run - < scripts/k6/orders.js
import http from "k6/http";
import { check } from "k6";

const BASE = __ENV.BASE || "http://localhost:8080";
const SYMBOLS = ["CDR", "PKO", "PKN", "PZU", "KGH", "ALE"];
const HEADERS = { headers: { "Content-Type": "application/json" } };

export const options = {
  scenarios: {
    // Stale tempo zamiast stalej liczby uzytkownikow: mierzymy czas odpowiedzi przy znanym obciazeniu,
    // a nie "ile wytrzyma, zanim kolejka urosnie" (tak k6 ukrylby opoznienia w czasie oczekiwania).
    orders: {
      executor: "constant-arrival-rate",
      rate: Number(__ENV.RATE || 1000), timeUnit: "1s", duration: __ENV.DURATION || "60s",
      preAllocatedVUs: 100, maxVUs: 500,
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    "http_req_duration{kind:order}": ["p(99)<50"],
  },
};

export function setup() {
  // Cena odniesienia ze srodka arkusza (albo stala, gdy arkusz pusty).
  const mids = {};
  for (const s of SYMBOLS) {
    const book = http.get(`${BASE}/api/instruments/${s}/book?levels=1`).json();
    const bid = Number(book.bids[0]?.price || 100), ask = Number(book.asks[0]?.price || bid);
    mids[s] = Math.round(((bid + ask) / 2) * 100);
  }
  return mids;
}

export default function (mids) {
  const symbol = SYMBOLS[Math.floor(Math.random() * SYMBOLS.length)];
  const side = Math.random() < 0.5 ? "BUY" : "SELL";
  const market = Math.random() < 0.2;
  // Limit w promieniu ~0,5% od srodka: czesc sie krzyzuje (transakcje), czesc czeka (glebokosc).
  const grosze = Math.max(1, Math.round(mids[symbol] * (1 + (Math.random() - 0.5) * 0.01)));
  const body = { side, type: market ? "MARKET" : "LIMIT", quantity: 1 + Math.floor(Math.random() * 20) };
  if (!market) body.price = (grosze / 100).toFixed(2);

  const res = http.post(`${BASE}/api/instruments/${symbol}/orders`, JSON.stringify(body),
    { ...HEADERS, tags: { kind: "order" } });
  check(res, { "201": (r) => r.status === 201 });

  if (res.status === 201 && !market && Math.random() < 0.3) {
    // Anulowanie moze dostac 404, gdy zlecenie zdazylo sie zrealizowac -- to poprawna odpowiedz.
    const id = res.json("orderId");
    http.del(`${BASE}/api/instruments/${symbol}/orders/${id}`, null,
      { tags: { kind: "cancel" }, responseCallback: http.expectedStatuses(200, 404) });
  }
}
