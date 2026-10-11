// Arkusz zlecen, tasma transakcji i kafelki spolek.
import { NAMES, compact, el, percent, price, qty, time, css } from "./format.js";
import { applyTrade } from "./chart.js";

const LEVELS = 12;

/** Arkusz: sprzedaz nad spreadem (najlepsza na dole), kupno pod nim; pasek = skumulowana ilosc. */
export function renderBook(book, { onPick, mine }) {
  const asks = book.asks.slice(0, LEVELS);
  const bids = book.bids.slice(0, LEVELS);
  const cumulate = (side) => { let s = 0; return side.map((l) => (s += l.quantity)); };
  const cumAsk = cumulate(asks), cumBid = cumulate(bids);
  const max = Math.max(cumAsk.at(-1) || 0, cumBid.at(-1) || 0, 1);
  const row = (l, cum) => el("div", {
    class: `book-row${mine.has(l.price) ? " mine" : ""}`,
    style: `--depth:${((cum / max) * 100).toFixed(1)}%`,
    title: `${l.orders} zlec.`,
    onclick: () => onPick(l.price),
  }, el("span", { class: "px" }, price(l.price)), el("span", {}, qty(l.quantity)), el("span", { class: "muted" }, qty(cum)));

  document.getElementById("asks").replaceChildren(...asks.map((l, i) => row(l, cumAsk[i])).reverse());
  document.getElementById("bids").replaceChildren(...bids.map((l, i) => row(l, cumBid[i])));
  document.getElementById("book-seq").textContent = `seq ${book.sequence}`;

  const bestAsk = asks[0]?.price, bestBid = bids[0]?.price;
  const spread = bestAsk && bestBid ? bestAsk - bestBid : null;
  document.getElementById("mid").textContent = bestAsk && bestBid ? price(Math.round((bestAsk + bestBid) / 2)) : "—";
  document.getElementById("spread-bps").textContent =
    spread != null ? `spread ${price(spread)} · ${((spread / bestBid) * 10000).toFixed(1)} bps` : "brak drugiej strony";
  document.getElementById("inst-spread").textContent = spread != null ? price(spread) : "—";
}

const tape = [];

export function pushTrade(trade, clear = false) {
  if (clear) tape.length = 0;
  if (trade) tape.unshift(trade);
  tape.length = Math.min(tape.length, 40);
  document.getElementById("tape").replaceChildren(...tape.map((t, i) => el("li", { class: i === 0 && trade ? "new" : "" },
    el("span", { class: "t" }, time(t.t)),
    el("span", { class: t.side === "BUY" ? "up" : "down" }, price(t.price)),
    el("span", {}, qty(t.quantity)))));
}

/** Kafelki spolek: cena, zmiana i mini-wykres z ostatnich 30 minut. */
export class Strip {
  constructor(symbols, onSelect) {
    this.tiles = new Map();
    const root = document.getElementById("strip");
    for (const s of symbols) {
      const parts = {
        price: el("span", { class: "tile-price" }, "—"),
        chg: el("span", { class: "tile-chg" }, ""),
        spark: el("canvas"),
      };
      const tile = el("button", { class: "tile", onclick: () => onSelect(s) },
        el("span", { class: "tile-sym" }, s), parts.price,
        el("span", { class: "tile-name" }, NAMES[s] || s), parts.chg, parts.spark);
      root.append(tile);
      this.tiles.set(s, { tile, ...parts, series: [], last: null });
    }
  }

  /** Transakcja na zywo: swiece 30-sekundowe mini-wykresu + cena. */
  trade(symbol, t, p, q) {
    applyTrade(this.tiles.get(symbol).series, 30, t, p, q, 60); // 60 x 30 s = okno "30 min" z naglowka
    this.update(symbol, p);
  }

  select(symbol) {
    for (const [s, t] of this.tiles) t.tile.classList.toggle("on", s === symbol);
  }

  series(symbol, candles) {
    const t = this.tiles.get(symbol);
    t.series = candles;
    this.update(symbol, candles.at(-1)?.c ?? null, false);
  }

  update(symbol, last, flash = true) {
    const t = this.tiles.get(symbol);
    if (flash && t.last != null && last !== t.last) {
      t.price.classList.remove("flash-up", "flash-down");
      void t.price.offsetWidth; // restart animacji
      t.price.classList.add(last > t.last ? "flash-up" : "flash-down");
    }
    t.last = last;
    t.price.textContent = price(last);
    const chg = percent(t.series[0]?.o, last);
    t.chg.textContent = chg.text;
    t.chg.className = `tile-chg ${chg.dir}`;
    spark(t.spark, t.series.map((k) => k.c), chg.dir);
  }

  stats(symbol) {
    const s = this.tiles.get(symbol).series;
    if (!s.length) return null;
    return { open: s[0].o, high: Math.max(...s.map((k) => k.h)), low: Math.min(...s.map((k) => k.l)),
      volume: compact(s.reduce((a, k) => a + k.v, 0)) };
  }
}

function spark(canvas, values, dir) {
  const dpr = window.devicePixelRatio || 1, w = canvas.clientWidth, h = canvas.clientHeight;
  if (!w) return;
  canvas.width = w * dpr; canvas.height = h * dpr;
  const ctx = canvas.getContext("2d");
  ctx.scale(dpr, dpr);
  if (values.length < 2) return;
  const hi = Math.max(...values), lo = Math.min(...values), span = hi - lo || 1;
  const col = dir === "down" ? css("--down") : css("--up");
  const pts = values.map((v, i) => [(i / (values.length - 1)) * w, h - 2 - ((v - lo) / span) * (h - 4)]);
  const grad = ctx.createLinearGradient(0, 0, 0, h);
  grad.addColorStop(0, col + "40"); grad.addColorStop(1, col + "00");
  ctx.beginPath(); pts.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
  ctx.strokeStyle = col; ctx.lineWidth = 1.4; ctx.stroke();
  ctx.lineTo(w, h); ctx.lineTo(0, h); ctx.closePath(); ctx.fillStyle = grad; ctx.fill();
}
