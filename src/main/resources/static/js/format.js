// Ceny przychodza z API jako tekst ("263.10") i tak samo wychodza. W srodku liczymy w groszach
// na liczbach calkowitych -- jak serwer. 0.1 + 0.2 w double to nie jest 0.3.

export const NAMES = {
  CDR: "CD Projekt", PKO: "PKO BP", PKN: "Orlen", PZU: "PZU", KGH: "KGHM", ALE: "Allegro",
};

/** "263.10" albo "263,1" -> 26310. Pusto/niepoprawnie -> null. */
export function toGrosze(text) {
  if (text == null) return null;
  const m = String(text).trim().replace(",", ".").match(/^(\d+)(?:\.(\d{0,2}))?$/);
  if (!m) return null;
  return Number(m[1]) * 100 + Number((m[2] || "").padEnd(2, "0"));
}

/** 26310 -> "263.10" (format API). */
export function toApi(grosze) {
  return `${Math.trunc(grosze / 100)}.${String(grosze % 100).padStart(2, "0")}`;
}

const PLN = new Intl.NumberFormat("pl-PL", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const INT = new Intl.NumberFormat("pl-PL");
const COMPACT = new Intl.NumberFormat("pl-PL", { notation: "compact", maximumFractionDigits: 1 });

export const price = (grosze) => (grosze == null ? "—" : PLN.format(grosze / 100));
export const qty = (n) => INT.format(n);
export const compact = (n) => COMPACT.format(n);
export const money = (grosze) => `${PLN.format(grosze / 100)} zł`;

export function percent(from, to) {
  if (!from || to == null) return { text: "—", dir: "" };
  const p = ((to - from) / from) * 100;
  const sign = p > 0 ? "+" : p < 0 ? "−" : "";
  return { text: `${sign}${Math.abs(p).toFixed(2).replace(".", ",")}%`, dir: p > 0 ? "up" : p < 0 ? "down" : "" };
}

export function time(ms, withSeconds = true) {
  return new Date(ms).toLocaleTimeString("pl-PL", {
    hour: "2-digit", minute: "2-digit", ...(withSeconds ? { second: "2-digit" } : {}),
  });
}

/** Maly helper do budowania DOM bez innerHTML z danymi (zadnego XSS z nazw czy komunikatow). */
export function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (k === "class") node.className = v;
    else if (k === "style") node.style.cssText = v;
    else if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
    else if (k === "dataset") Object.assign(node.dataset, v);
    else node.setAttribute(k, v);
  }
  for (const c of children) if (c != null) node.append(c);
  return node;
}

export function css(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}
