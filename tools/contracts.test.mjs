import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";

test("release identity and protocol major are frozen", () => {
  const build = fs.readFileSync("app/build.gradle.kts", "utf8");
  const caps = JSON.parse(fs.readFileSync("api/capabilities.json", "utf8"));
  assert.match(build, /applicationId = "io\.github\.zhyuzh3d\.hermit"/);
  assert.match(build, /minSdk = 31/);
  assert.equal(caps.apiMajor, 1);
});

test("bridge has document handshake and no JavaScript interface", () => {
  const bridge = fs.readFileSync("app/src/main/assets/bridge/hermit-v1.js", "utf8");
  const native = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/bridge/BridgeController.kt", "utf8");
  assert.match(bridge, /kind: "hello"/);
  assert.match(native, /addWebMessageListener/);
  assert.match(native, /isMainFrame/);
  assert.match(native, /MAX_IN_FLIGHT = 16/);
  assert.match(bridge, /MAX_PENDING = 16/);
  assert.doesNotMatch(native, /addJavascriptInterface/);
});

test("backup contract excludes ambient trust state", () => {
  const source = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/backup/BackupCoordinator.kt", "utf8");
  for (const excluded of ["webProfile", "cookies", "webStorage", "permissions", "developerTokens"]) assert.ok(source.includes(excluded));
});
