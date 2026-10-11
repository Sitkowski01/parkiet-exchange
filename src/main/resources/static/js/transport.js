// Skad terminal bierze dane: z gieldy (REST + WebSocket) albo z nagrania -- demo bez serwera,
// wlaczane atrybutem <html data-replay="recording.json"> (scripts/demo/build.mjs).
import * as live from "./market.js";
import * as replay from "./replay.js";

export const { api, candles, stream } = document.documentElement.dataset.replay ? replay : live;
