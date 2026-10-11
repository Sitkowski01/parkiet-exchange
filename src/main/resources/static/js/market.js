// Polaczenie z gielda: REST (zlecenia, swiece) i WebSocket (arkusz + transakcje na zywo).
import { toGrosze } from "./format.js";

export async function api(path, options = {}) {
  const res = await fetch(path, {
    ...options,
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
  });
  const body = res.status === 204 ? null : await res.json().catch(() => null);
  if (!res.ok) {
    // ProblemDetail (RFC 9457) z serwera: detail mowi po ludzku, co poszlo nie tak.
    const err = new Error(body?.detail || body?.title || `HTTP ${res.status}`);
    err.status = res.status;
    throw err;
  }
  return body;
}

/** Swiece z dziennika zdarzen; ceny od razu w groszach. */
export async function candles(symbol, seconds, count) {
  const list = await api(`/api/instruments/${symbol}/candles?seconds=${seconds}&count=${count}`);
  return list.map((c) => ({
    t: Date.parse(c.time), o: toGrosze(c.open), h: toGrosze(c.high), l: toGrosze(c.low),
    c: toGrosze(c.close), v: c.volume,
  }));
}

const level = (l) => ({ price: toGrosze(l.price), quantity: l.quantity, orders: l.orders });

/**
 * Strumien notowan z ponownym laczeniem. Zszywanie po sequence: obraz arkusza starszy niz
 * ostatnio widziany jest odrzucany (moze przyjsc po nowszym po ponownym polaczeniu).
 */
export function stream(symbols, { onBook, onTrade, onStatus }) {
  const lastBook = new Map();
  let delay = 1000;
  let socket;

  function open() {
    const proto = location.protocol === "https:" ? "wss" : "ws";
    socket = new WebSocket(`${proto}://${location.host}/ws/market?symbols=${symbols.join(",")}`);
    socket.onopen = () => { delay = 1000; onStatus("live"); };
    socket.onmessage = (e) => {
      const msg = JSON.parse(e.data);
      if (msg.type === "book") {
        if (msg.sequence < (lastBook.get(msg.symbol) ?? -1)) return;
        lastBook.set(msg.symbol, msg.sequence);
        onBook({ symbol: msg.symbol, sequence: msg.sequence, bids: msg.bids.map(level), asks: msg.asks.map(level) });
      } else if (msg.type === "trade") {
        onTrade({
          symbol: msg.symbol, sequence: msg.sequence, t: Date.parse(msg.timestamp),
          price: toGrosze(msg.price), quantity: msg.quantity, side: msg.takerSide,
        });
      }
    };
    socket.onclose = (e) => {
      onStatus("down", e.reason);
      // Serwer zamyka wolnego klienta (1008 too slow) -- po ponownym polaczeniu dostajemy swiezy obraz.
      setTimeout(open, delay);
      delay = Math.min(delay * 2, 10000);
    };
  }

  open();
  return () => socket?.close();
}
