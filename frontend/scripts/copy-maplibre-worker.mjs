// Vite never detects/bundles maplibre-gl's internal web worker (used to
// fetch/decode vector tiles), since it's instantiated dynamically inside
// the library rather than via a static `new Worker(new URL(...))` Vite
// can analyze - so `public/` needs its own copy, served unbundled, that
// TacticalMap.tsx points at via `setWorkerUrl()`. The worker module itself
// imports maplibre-gl-shared.mjs as a relative sibling, so both must be
// copied together or the worker's module import 404s (silently - no
// console error, tiles just never start loading). Run automatically
// before dev/build (see package.json) instead of committing generated
// vendor files, so it always matches the installed maplibre-gl version.
import { copyFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const src = join(here, "..", "node_modules", "maplibre-gl", "dist");
const dest = join(here, "..", "public");

mkdirSync(dest, { recursive: true });
for (const file of ["maplibre-gl-worker.mjs", "maplibre-gl-shared.mjs"]) {
  copyFileSync(join(src, file), join(dest, file));
}
