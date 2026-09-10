import fs from "node:fs";

for (const path of [
  "app/src/main/assets/bridge/hermit-v1.js",
  "app/src/main/assets/store/index.html",
  "app/src/main/assets/store/store.css",
  "app/src/main/assets/store/store.js",
  "sdk/hermit-api.d.ts"
]) {
  if (!fs.statSync(path).isFile || fs.statSync(path).size === 0) throw new Error(`Missing Web artifact: ${path}`);
}
console.log("Hermit Web assets are ready for Android packaging.");
