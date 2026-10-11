// Parkiet: sklada panele i laczy je ze strumieniem notowan.
import { NAMES, compact, percent, price, time } from "./format.js";
import { api, candles, stream } from "./transport.js";
import { CandleChart } from "./chart.js";
import { Strip, pushTrade, renderBook } from "./panels.js";
import { fill, log, myPrices, renderOrders, ticket } from "./trading.js";

const SPARK_SECONDS = 30, SPARK_COUNT = 60; // mini-wykresy: ostatnie 30 minut
const $ = (id) => document.getElementById(id);

// Bez listy spolek nie ma czego pokazac: gdy serwer nie odpowiada, mowimy o tym i probujemy dalej.
async function instruments() {
  for (let delay = 1000; ; delay = Math.min(delay * 2, 10000)) {
    try { return [...await api("/api/instruments")]; } catch {
      $("status").className = "status down";
      $("status").querySelector(".status-text").textContent = "serwer nie odpowiada · ponawiam";
      await new Promise((r) => setTimeout(r, delay));
    }
  }
}

const symbols = await instruments();
const books = new Map();
const recent = new Map(symbols.map((s) => [s, []]));
let selected = symbols.includes(location.hash.slice(1)) ? location.hash.slice(1) : symbols[0];
let seconds = 5;
let turnover = 0;
const stamps = [];

const chart = new CandleChart($("chart"));
const strip = new Strip(symbols, select);
const form = ticket(() => selected, () => books.get(selected), () => drawBook());

async function select(symbol) {
  selected = symbol;
  history.replaceState(null, "", `#${symbol}`);
  strip.select(symbol);
  $("inst-symbol").textContent = symbol;
  $("inst-name").textContent = NAMES[symbol] || "";
  form.clearPrice();
  pushTrade(null, true);
  recent.get(symbol).slice(-40).forEach((t) => pushTrade(t));
  drawBook();
  form.refresh();
  header();
  await loadChart();
}

async function loadChart() {
  const symbol = selected, s = seconds;
  const list = await candles(symbol, s, 240).catch(() => []);
  if (symbol !== selected || s !== seconds) return; // uzytkownik zdazyl przelaczyc
  chart.set(symbol, s, list);
  $("chart-empty").hidden = list.length > 0;
}

function header() {
  const st = strip.stats(selected);
  const last = strip.tiles.get(selected).last;
  $("inst-last").textContent = price(last);
  const chg = percent(st?.open, last);
  $("inst-change").textContent = st ? `${chg.text} · 30 min` : "";
  $("inst-change").className = `change ${chg.dir}`;
  $("inst-high").textContent = st ? price(st.high) : "—";
  $("inst-low").textContent = st ? price(st.low) : "—";
  $("inst-volume").textContent = st?.volume ?? "—";
  document.title = last ? `${selected} ${price(last)} · Parkiet` : "Parkiet — silnik giełdy";
}

function drawBook() {
  const book = books.get(selected);
  if (!book) return;
  renderBook(book, {
    mine: myPrices(selected),
    onPick: (p) => form.pick(p, book.asks.some((l) => l.price === p) ? "BUY" : "SELL"),
  });
  form.refresh();
}

$("intervals").addEventListener("click", (e) => {
  const s = Number(e.target.dataset.seconds);
  if (!s) return;
  seconds = s;
  $("intervals").querySelectorAll("button").forEach((b) => b.classList.toggle("on", b === e.target));
  loadChart();
});

document.querySelectorAll("[data-tab]").forEach((tab) => tab.addEventListener("click", () => {
  document.querySelectorAll("[data-tab]").forEach((t) => t.classList.toggle("on", t === tab));
  $("tab-orders").hidden = tab.dataset.tab !== "orders";
  $("tab-events").hidden = tab.dataset.tab !== "events";
}));

// Najpierw historia (mini-wykresy), potem strumien -- transakcje dopisuja sie do gotowych swiec.
const loadSparks = () => Promise.all(symbols.map(async (s) => strip.series(s, await candles(s, SPARK_SECONDS, SPARK_COUNT).catch(() => []))));
await loadSparks();
renderOrders();
select(selected);

stream(symbols, {
  onStatus(state, reason, reconnected) {
    // Transakcje z czasu przerwy nie przyjda strumieniem -- swiece bierzemy od nowa z dziennika.
    if (reconnected) { loadSparks().then(header); loadChart(); }
    // replay: demo z nagrania (replay.js) -- kropka jak na zywo, ale napis mowi prawde.
    $("status").className = `status ${state === "replay" ? "live" : state}`;
    $("status").querySelector(".status-text").textContent = state === "live" ? "na żywo"
      : state === "replay" ? "demo · nagranie sesji" : `rozłączono${reason ? `: ${reason}` : ""} · ponawiam`;
    $("replay-note").hidden = state !== "replay";
  },
  onBook(book) {
    books.set(book.symbol, book);
    if (book.symbol === selected) { drawBook(); form.suggest(); }
  },
  onTrade(t) {
    turnover += t.price * t.quantity; stamps.push(performance.now());
    const list = recent.get(t.symbol);
    list.push(t);
    if (list.length > 40) list.shift();
    strip.trade(t.symbol, t.t, t.price, t.quantity);
    if (fill(t.symbol, t.maker, t.quantity) && t.symbol === selected) drawBook();
    log({ type: "TRADE", symbol: t.symbol, sequence: t.sequence, quantity: t.quantity, price: price(t.price), takerSide: t.side });
    if (t.symbol === selected) {
      chart.trade(t.t, t.price, t.quantity);
      $("chart-empty").hidden = true;
      pushTrade(t);
      header();
    }
  },
});

setInterval(() => {
  const now = performance.now();
  while (stamps.length && now - stamps[0] > 5000) stamps.shift();
  $("rate").textContent = (stamps.length / 5).toFixed(1);
  $("turnover").textContent = compact(turnover / 100) + " zł";
  $("clock").textContent = time(Date.now());
}, 500);
