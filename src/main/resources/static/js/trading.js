// Formularz zlecenia, "moje zlecenia" i dziennik zdarzen. Zlecenia z tej przegladarki pamietamy
// w localStorage -- gielda nie ma kont, wiec to jedyne miejsce, gdzie wiadomo, ktore sa "moje".
import { api } from "./market.js";
import { el, money, price, qty, toApi, toGrosze } from "./format.js";

const KEY = "parkiet.orders";
const LABEL = { resting: "w arkuszu", filled: "zrealizowane", partial: "częściowo", cancelled: "anulowane", gone: "nieaktywne" };
let orders = load();

function load() {
  try { return JSON.parse(localStorage.getItem(KEY)) || []; } catch { return []; }
}
function save() {
  try { localStorage.setItem(KEY, JSON.stringify(orders.slice(0, 50))); } catch { /* tryb prywatny */ }
}

/** Ceny moich zlecen czekajacych w arkuszu spolki -- arkusz zaznacza je kropka. */
export function myPrices(symbol) {
  return new Set(orders.filter((o) => o.symbol === symbol && o.state === "resting").map((o) => o.price));
}

export function renderOrders() {
  document.getElementById("orders-count").textContent = orders.filter((o) => o.state === "resting").length;
  document.getElementById("orders-empty").hidden = orders.length > 0;
  document.getElementById("orders").replaceChildren(...orders.map((o) => el("tr", {},
    el("td", { class: "muted" }, o.id), el("td", {}, o.symbol),
    el("td", { class: o.side === "BUY" ? "up" : "down" }, o.side === "BUY" ? "kupno" : "sprzedaż"),
    el("td", {}, o.type === "MARKET" ? "PKC" : "limit"),
    el("td", {}, o.price ? price(o.price) : "—"), el("td", {}, qty(o.quantity)),
    el("td", {}, el("span", { class: `state ${o.state}` }, LABEL[o.state])),
    el("td", {}, o.state === "resting" ? el("button", { class: "cancel", onclick: () => cancel(o) }, "anuluj") : ""))));
}

async function cancel(order) {
  try {
    await api(`/api/instruments/${order.symbol}/orders/${order.id}`, { method: "DELETE" });
    order.state = "cancelled";
    log({ type: "CANCELLED", symbol: order.symbol, text: `#${order.id} anulowane` });
  } catch (e) {
    // 404: zlecenia nie ma juz w arkuszu -- ktos je zrealizowal, zanim zdazylismy anulowac.
    order.state = e.status === 404 ? "gone" : order.state;
    toast(e.status === 404 ? "Zlecenie już zrealizowane" : e.message, "err");
  }
  save(); renderOrders(); onChange();
}

let onChange = () => {};

/** Formularz: strona, typ, cena, ilosc -> POST. Wynik opisuje, co dokladnie zrobil silnik. */
export function ticket(getSymbol, getBook, changed) {
  onChange = changed;
  const form = document.getElementById("ticket");
  const priceIn = document.getElementById("price"), qtyIn = document.getElementById("quantity");
  const submit = document.getElementById("submit");
  const state = { side: "BUY", type: "LIMIT" };

  const refresh = () => {
    form.querySelectorAll("[data-side]").forEach((b) => b.classList.toggle("on", b.dataset.side === state.side));
    form.querySelectorAll("[data-type]").forEach((b) => b.classList.toggle("on", b.dataset.type === state.type));
    document.getElementById("price-field").classList.toggle("disabled", state.type === "MARKET");
    priceIn.disabled = state.type === "MARKET";
    submit.className = `submit ${state.side === "BUY" ? "buy" : "sell"}`;
    submit.textContent = `${state.side === "BUY" ? "Kup" : "Sprzedaj"} ${getSymbol()}`;
    const book = getBook();
    const ref = state.type === "MARKET" ? (state.side === "BUY" ? book?.asks[0] : book?.bids[0])?.price : toGrosze(priceIn.value);
    const n = Number(qtyIn.value);
    document.getElementById("estimate").textContent = ref && n > 0 ? `${state.type === "MARKET" ? "≈ " : ""}${money(ref * n)}` : "—";
  };

  form.querySelectorAll("[data-side]").forEach((b) => b.addEventListener("click", () => { state.side = b.dataset.side; refresh(); }));
  form.querySelectorAll("[data-type]").forEach((b) => b.addEventListener("click", () => { state.type = b.dataset.type; refresh(); }));
  document.getElementById("quick").addEventListener("click", (e) => { if (e.target.dataset.q) { qtyIn.value = e.target.dataset.q; refresh(); } });
  priceIn.addEventListener("input", refresh);
  qtyIn.addEventListener("input", refresh);

  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    const symbol = getSymbol(), p = toGrosze(priceIn.value), n = Number(qtyIn.value);
    const body = { side: state.side, type: state.type, quantity: n, ...(state.type === "LIMIT" ? { price: p == null ? priceIn.value : toApi(p) } : {}) };
    submit.disabled = true;
    try {
      const res = await api(`/api/instruments/${symbol}/orders`, { method: "POST", body: JSON.stringify(body) });
      const filled = res.events.filter((ev) => ev.type === "TRADE").reduce((a, ev) => a + ev.quantity, 0);
      const rested = res.events.find((ev) => ev.type === "RESTED");
      orders.unshift({ id: res.orderId, symbol, side: state.side, type: state.type, price: state.type === "LIMIT" ? p : null,
        quantity: n, state: rested ? "resting" : filled ? "filled" : "gone" });
      res.events.forEach((ev) => log({ ...ev, symbol }));
      toast(describe(res, filled, rested), "ok");
      save(); renderOrders(); changed();
    } catch (err) {
      toast(err.message, "err");
    } finally {
      submit.disabled = false;
    }
  });

  // Pusta cena po zmianie spolki: podpowiadamy najlepsza oferte po drugiej stronie.
  const suggest = () => {
    const book = getBook();
    const best = (state.side === "BUY" ? book?.asks[0] : book?.bids[0])?.price;
    if (best && !priceIn.value) { priceIn.value = toApi(best).replace(".", ","); refresh(); }
  };

  return {
    refresh,
    suggest,
    clearPrice() { priceIn.value = ""; },
    /** Klik w poziom arkusza: cena do formularza, strona przeciwna (bierzemy z tej strony arkusza). */
    pick(p, side) { state.type = "LIMIT"; state.side = side; priceIn.value = toApi(p).replace(".", ","); refresh(); },
  };
}

function describe(res, filled, rested) {
  const avg = filled ? res.events.filter((e) => e.type === "TRADE").reduce((a, e) => a + toGrosze(e.price) * e.quantity, 0) / filled : 0;
  if (filled && rested) return `#${res.orderId}: ${qty(filled)} szt. po śr. ${price(Math.round(avg))}, reszta czeka w arkuszu`;
  if (filled) return `#${res.orderId}: zrealizowano ${qty(filled)} szt. po śr. ${price(Math.round(avg))} zł`;
  if (rested) return `#${res.orderId}: czeka w arkuszu po ${rested.price.replace(".", ",")} zł`;
  return `#${res.orderId}: brak ofert po drugiej stronie — PKC anulowane`;
}

const events = [];
export function log(ev) {
  const text = ev.text || (ev.type === "TRADE"
    ? `${ev.symbol} ${qty(ev.quantity)} @ ${ev.price}${ev.takerSide ? ` · aktywny ${ev.takerSide === "BUY" ? "kupujący" : "sprzedający"}` : ""}`
    : ev.type === "RESTED" ? `${ev.symbol} #${ev.orderId} ${ev.side} ${qty(ev.quantity)} @ ${ev.price}` : `${ev.symbol} #${ev.orderId} ${qty(ev.quantity)} · ${ev.reason}`);
  events.unshift(el("li", {}, el("span", { class: "seq" }, ev.sequence ? `seq ${ev.sequence}` : "http"),
    el("span", { class: `ty ${ev.type}` }, ev.type), text));
  events.length = Math.min(events.length, 200);
  document.getElementById("events").replaceChildren(...events);
}

let hide;
export function toast(text, kind = "") {
  const t = document.getElementById("toast");
  t.textContent = text;
  t.className = `toast show ${kind}`;
  clearTimeout(hide);
  hide = setTimeout(() => t.classList.remove("show"), 3800);
}
