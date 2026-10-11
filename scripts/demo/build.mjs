// Sklada statyczne demo terminala: te same pliki co w aplikacji + nagranie, bez serwera.
//   node scripts/demo/build.mjs [nagranie=build/demo/recording.json] [katalog=build/demo/site]
// Katalog mozna wgrac na dowolny hosting plikow (np. do public/ strony z portfolio).
import { cpSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const [recording = "build/demo/recording.json", out = "build/demo/site"] = process.argv.slice(2);
const STATIC = new URL("../../src/main/resources/static/", import.meta.url);

rmSync(out, { recursive: true, force: true });
cpSync(STATIC, out, { recursive: true });
cpSync(recording, join(out, "recording.json"));
const index = join(out, "index.html");
writeFileSync(index, readFileSync(index, "utf8").replace('<html lang="pl">', '<html lang="pl" data-replay="recording.json">'));
console.log(`${out}: demo z nagrania ${recording}`);
