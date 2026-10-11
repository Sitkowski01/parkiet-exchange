// Wykres swiecowy na canvasie, bez bibliotek: swiece, wolumen, linia ostatniej ceny, krzyz.
import { css, price, qty, time } from "./format.js";

const AXIS_W = 70;
const TIME_H = 24;
const TOP = 16;
const MIN_STEP = 9;

export class CandleChart {
  constructor(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext("2d");
    this.candles = [];
    this.seconds = 5;
    this.symbol = "";
    this.mouse = null;
    this.queued = false;
    new ResizeObserver(() => this.draw()).observe(canvas);
    canvas.addEventListener("mousemove", (e) => {
      const r = canvas.getBoundingClientRect();
      this.mouse = { x: e.clientX - r.left, y: e.clientY - r.top };
      this.draw();
    });
    canvas.addEventListener("mouseleave", () => { this.mouse = null; this.draw(); });
  }

  set(symbol, seconds, candles) {
    Object.assign(this, { symbol, seconds, candles });
    this.draw();
  }

  /** Transakcja na zywo: dopisuje do ostatniej swiecy albo otwiera nowa. */
  trade(t, p, q) {
    applyTrade(this.candles, this.seconds, t, p, q);
    this.draw();
  }

  draw() {
    if (this.queued) return;
    this.queued = true;
    requestAnimationFrame(() => { this.queued = false; this.paint(); });
  }

  paint() {
    const { canvas, ctx } = this;
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth, h = canvas.clientHeight;
    if (!w || !h) return;
    if (canvas.width !== w * dpr) { canvas.width = w * dpr; canvas.height = h * dpr; }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);

    const c = { up: css("--up"), down: css("--down"), line: css("--line"), muted: css("--muted"),
      text: css("--text"), amber: css("--amber"), panel: css("--panel-2"), mono: css("--mono") };
    const plotW = w - AXIS_W, plotH = h - TIME_H - TOP;
    const volH = plotH * 0.18, priceH = plotH - volH - 8;
    const count = Math.max(10, Math.floor(plotW / MIN_STEP));
    const view = this.candles.slice(-count);
    const step = plotW / count;
    const x0 = plotW - view.length * step;

    // Znak wodny: symbol spolki.
    ctx.fillStyle = "rgba(255,255,255,0.025)";
    ctx.font = `700 ${Math.min(120, h / 3)}px Inter, sans-serif`;
    ctx.textBaseline = "middle";
    ctx.fillText(this.symbol, 24, TOP + priceH / 2);
    if (!view.length) return;

    let hi = Math.max(...view.map((k) => k.h)), lo = Math.min(...view.map((k) => k.l));
    const pad = Math.max((hi - lo) * 0.12, hi * 0.0008);
    hi += pad; lo -= pad;
    const y = (p) => TOP + ((hi - p) / (hi - lo)) * priceH;
    const vMax = Math.max(...view.map((k) => k.v));

    const lastY = y(view.at(-1).c);
    // Siatka i os cen.
    ctx.font = `11px ${c.mono}`;
    ctx.textBaseline = "middle";
    const tick = niceStep((hi - lo) / 5);
    ctx.strokeStyle = c.line; ctx.lineWidth = 1;
    for (let p = Math.ceil(lo / tick) * tick; p <= hi; p += tick) {
      const yy = Math.round(y(p)) + 0.5;
      ctx.beginPath(); ctx.moveTo(0, yy); ctx.lineTo(plotW, yy); ctx.stroke();
      // Etykiety pod znacznikiem ostatniej ceny bylyby nieczytelne.
      if (Math.abs(yy - lastY) > 14) { ctx.fillStyle = c.muted; ctx.fillText(price(p), plotW + 8, yy); }
    }

    // Os czasu: etykieta co ~110 px.
    const every = Math.max(1, Math.round(110 / step));
    ctx.textBaseline = "alphabetic"; ctx.textAlign = "center";
    view.forEach((k, i) => {
      if ((view.length - 1 - i) % every) return;
      const xx = x0 + i * step + step / 2;
      ctx.fillStyle = c.muted; ctx.fillText(time(k.t, this.seconds < 60), xx, h - 7);
      ctx.strokeStyle = c.line; ctx.beginPath(); ctx.moveTo(Math.round(xx) + 0.5, TOP); ctx.lineTo(Math.round(xx) + 0.5, TOP + plotH); ctx.stroke();
    });
    ctx.textAlign = "left";

    // Swiece i wolumen.
    const body = Math.max(1, Math.floor(step * 0.62));
    view.forEach((k, i) => {
      const xx = Math.round(x0 + i * step + step / 2);
      const col = k.c >= k.o ? c.up : c.down;
      ctx.fillStyle = col; ctx.strokeStyle = col;
      ctx.beginPath(); ctx.moveTo(xx + 0.5, y(k.h)); ctx.lineTo(xx + 0.5, y(k.l)); ctx.stroke();
      const top = y(Math.max(k.o, k.c)), bh = Math.max(1, y(Math.min(k.o, k.c)) - top);
      ctx.fillRect(xx - Math.floor(body / 2), top, body, bh);
      const vh = (k.v / vMax) * volH;
      ctx.globalAlpha = 0.28;
      ctx.fillRect(xx - Math.floor(body / 2), TOP + plotH - vh, body, vh);
      ctx.globalAlpha = 1;
    });

    // Ostatnia cena: przerywana linia i etykieta na osi.
    const last = view.at(-1), prev = view.at(-2);
    const lastCol = !prev || last.c >= prev.c ? c.up : c.down;
    const ly = Math.round(y(last.c)) + 0.5;
    ctx.setLineDash([4, 4]); ctx.strokeStyle = lastCol;
    ctx.beginPath(); ctx.moveTo(0, ly); ctx.lineTo(plotW, ly); ctx.stroke(); ctx.setLineDash([]);
    label(ctx, price(last.c), plotW + 2, ly, lastCol, "#05070a");

    if (this.mouse && this.mouse.x < plotW && this.mouse.y > TOP && this.mouse.y < TOP + priceH) {
      const { x: mx, y: my } = this.mouse;
      ctx.strokeStyle = c.muted; ctx.setLineDash([3, 3]);
      ctx.beginPath(); ctx.moveTo(mx + 0.5, TOP); ctx.lineTo(mx + 0.5, TOP + plotH);
      ctx.moveTo(0, my + 0.5); ctx.lineTo(plotW, my + 0.5); ctx.stroke(); ctx.setLineDash([]);
      label(ctx, price(Math.round(hi - ((my - TOP) / priceH) * (hi - lo))), plotW + 2, my, c.panel, c.text);
      const k = view[Math.floor((mx - x0) / step)];
      if (k) {
        ctx.font = `12px ${c.mono}`; ctx.fillStyle = c.muted; ctx.textBaseline = "top";
        ctx.fillText(`${time(k.t)}  O ${price(k.o)}  H ${price(k.h)}  L ${price(k.l)}  C ${price(k.c)}  V ${qty(k.v)}`, 12, 4);
      }
    }
  }
}

function label(ctx, text, x, y, bg, fg) {
  ctx.font = `600 11px ${css("--mono")}`;
  const w = ctx.measureText(text).width + 12;
  ctx.fillStyle = bg;
  ctx.beginPath(); ctx.roundRect(x, y - 10, w, 20, 4); ctx.fill();
  ctx.fillStyle = fg; ctx.textBaseline = "middle"; ctx.fillText(text, x + 6, y);
}

/** Krok siatki 1/2/5 x 10^n groszy -- okragle ceny na osi. */
function niceStep(raw) {
  const pow = 10 ** Math.floor(Math.log10(Math.max(raw, 1)));
  const n = raw / pow;
  return (n < 1.5 ? 1 : n < 3.5 ? 2 : n < 7.5 ? 5 : 10) * pow;
}

/** Dopisuje transakcje do swiec o przedziale `seconds` (te same granice co date_bin na serwerze). */
export function applyTrade(candles, seconds, t, p, q) {
  const bucket = Math.floor(t / (seconds * 1000)) * seconds * 1000;
  const last = candles.at(-1);
  if (last && last.t === bucket) {
    last.h = Math.max(last.h, p); last.l = Math.min(last.l, p); last.c = p; last.v += q;
  } else if (!last || bucket > last.t) {
    candles.push({ t: bucket, o: p, h: p, l: p, c: p, v: q });
    if (candles.length > 1000) candles.shift();
  }
}
