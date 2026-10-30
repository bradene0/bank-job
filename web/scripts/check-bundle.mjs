// Fails if any prompt fragment from content/tiles.yaml made it into the built game (ADR-0003):
// game-api assembles Penny's prompt server-side, and the browser only ever knows tile ids.
import { readFileSync, readdirSync, statSync } from "node:fs";
import path from "node:path";
import { parse } from "yaml";

const web = path.resolve(import.meta.dirname, "..");
const contentDir = process.env.BANKJOB_CONTENT_DIR ?? path.resolve(web, "../content");
const tiles = parse(readFileSync(path.join(contentDir, "tiles.yaml"), "utf8"));
const fragments = Object.values(tiles.slots).flatMap((slot) => slot.tiles.map((t) => t.fragment));

function files(dir) {
  return readdirSync(dir).flatMap((name) => {
    const full = path.join(dir, name);
    return statSync(full).isDirectory() ? files(full) : [full];
  });
}

const leaks = [];
for (const file of files(path.join(web, "dist"))) {
  const text = readFileSync(file, "utf8");
  for (const fragment of fragments) if (text.includes(fragment)) leaks.push(`${path.relative(web, file)}: "${fragment}"`);
}
if (leaks.length > 0) {
  console.error(`Prompt fragments found in the bundle:\n${leaks.join("\n")}`);
  process.exit(1);
}
console.log(`check:bundle: none of ${fragments.length} prompt fragments are in dist/`);
