import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";

test("release identity and protocol major are frozen", () => {
  const build = fs.readFileSync("app/build.gradle.kts", "utf8");
  const caps = JSON.parse(fs.readFileSync("api/capabilities.json", "utf8"));
  assert.match(build, /applicationId = "io\.github\.zhyuzh3d\.hermit"/);
  assert.match(build, /minSdk = 29/);
  assert.equal(caps.apiMajor, 1);
});

test("bridge keeps the isolated transport and an explicit legacy fallback", () => {
  const bridge = fs.readFileSync("app/src/main/assets/bridge/hermit-v1.js", "utf8");
  const native = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/bridge/BridgeController.kt", "utf8");
  const legacy = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/bridge/LegacyBridgeController.kt", "utf8");
  const bootstrap = fs.readFileSync("app/src/main/assets/bridge/legacy-bootstrap.js", "utf8");
  assert.match(bridge, /kind: "hello"/);
  assert.match(native, /addWebMessageListener/);
  assert.match(native, /isMainFrame/);
  assert.match(native, /MAX_IN_FLIGHT = 16/);
  assert.match(bridge, /MAX_PENDING = 16/);
  assert.match(bridge, /audio: namespace\("audio"\)/);
  assert.doesNotMatch(native, /addJavascriptInterface/);
  assert.match(legacy, /addJavascriptInterface/);
  assert.match(legacy, /weakly isolated/);
  assert.match(bootstrap, /__hermitLegacyNativeV1/);
});

test("backup contract excludes ambient trust state", () => {
  const source = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/backup/BackupCoordinator.kt", "utf8");
  for (const excluded of ["webProfile", "cookies", "webStorage", "permissions", "developerTokens"]) assert.ok(source.includes(excluded));
});

test("all upstream free font assets referenced by the bundled CSS are present", () => {
  const root = "app/src/main/assets/shared/fontawesome";
  const css = fs.readFileSync(root + "/css/all.min.css", "utf8");
  const files = [...css.matchAll(/url\((?:['"])?\.\.\/webfonts\/([^)'"?]+)(?:[^)]*)\)/g)].map(match => match[1]);
  assert.ok(files.length >= 3);
  for (const file of files) assert.equal(fs.readFileSync(root + "/webfonts/" + file).subarray(0, 4).toString(), "wOF2");
  assert.ok(fs.readFileSync(root + "/LICENSE.txt", "utf8").includes("SIL"));
});

test("agent catalog, guide snapshots and shared-password authority stay aligned", () => {
  const root = "app/src/main/assets/agent/";
  const tools = JSON.parse(fs.readFileSync(root + "tools.json", "utf8"));
  const server = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/deploy/AgentDevelopmentServer.kt", "utf8");
  assert.equal(new Set(tools.map(tool => tool.name)).size, 12);
  for (const tool of tools) {
    assert.ok(server.includes(`"${tool.name}" ->`));
    assert.equal(tool.inputSchema.additionalProperties, false);
  }
  assert.doesNotMatch(server, /class Pairing|class Client|pairUrl|phone-approved/);
  assert.match(server, /commitGuard = \{ guarded\(authorization/);
  assert.equal(fs.readFileSync(root + "webapp-authoring.md", "utf8"), fs.readFileSync("docs/webapp-authoring.md", "utf8"));
  assert.equal(fs.readFileSync(root + "hermit-api.d.ts", "utf8"), fs.readFileSync("sdk/hermit-api.d.ts", "utf8"));
});

test("store navigation, favorites, support fallback and QR bridge remain wired", () => {
  const html = fs.readFileSync("app/src/main/assets/store/index.html", "utf8");
  const script = fs.readFileSync("app/src/main/assets/store/store.js", "utf8");
  const css = fs.readFileSync("app/src/main/assets/store/store.css", "utf8");
  const tabs = [...html.matchAll(/<nav class="bottom-nav[^"]*"[\s\S]*?<\/nav>/g)][0]?.[0] || "";
  assert.deepEqual([...tabs.matchAll(/data-view="([^"]+)"/g)].map(match => match[1]), ["favorites", "all", "development", "settings", "support"]);
  assert.equal((tabs.match(/class="nav-icon"/g) || []).length, 5);
  assert.match(css, /grid-template-columns:repeat\(5,minmax\(0,1fr\)\)/);
  assert.match(script, /host\.apps\.favorite/);
  assert.match(script, /host\.apps\.scanQr/);
  assert.doesNotMatch(script, /host\.support\.open/);
  assert.match(html, /10knet·zhyuzh3d/);
  for (const id of ["addFolder", "addUrl", "scanQr", "topApkVersion", "topWebVersion"]) assert.match(html, new RegExp(`id="${id}"`));
  assert.match(script, /host\.apps\.pickDirectory/);
  assert.match(script, /host\.apps\.confirmDirectory/);
  assert.match(script, /host\.apps\.pickIcon/);
  assert.match(css, /bottom-nav \.nav-icon\{margin:0 auto 4px!important\}/);
  assert.match(css, /\.agent-address-row \{ display:flex; align-items:flex-end;/);
  assert.doesNotMatch(css, /\.agent-address-row \{[^}]*flex-direction:column/);
  assert.match(css, /\.agent-secret button \{ align-self:flex-end;[^}]*height:48px;/);
});

test("official shell supports live website preview and explicit local refresh", () => {
  const activity = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/MainActivity.kt", "utf8");
  const manager = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/runtime/OfficialShellManager.kt", "utf8");
  const bridge = fs.readFileSync("app/src/main/assets/bridge/hermit-v1.js", "utf8");
  const html = fs.readFileSync("app/src/main/assets/store/index.html", "utf8");
  const script = fs.readFileSync("app/src/main/assets/store/store.js", "utf8");
  assert.match(manager, /OFFICIAL_ORIGIN = "https:\/\/hermit\.10knet\.com"/);
  assert.match(manager, /ONLINE_URL = "\$OFFICIAL_ORIGIN\/shell\/index\.html"/);
  assert.match(manager, /Mode\.ONLINE/);
  assert.match(activity, /RuntimeRole\.STORE/);
  assert.match(activity, /LOAD_NO_CACHE/);
  assert.match(activity, /正在加载官网界面/);
  assert.match(activity, /local-fallback/);
  assert.match(bridge, /shell: namespace\("host\.shell"\)/);
  for (const id of ["shellVersionSwitch", "useLocalShell", "updateLocalShell"]) assert.match(html, new RegExp(`id="${id}"`));
  assert.match(script, /host\.shell\.setMode/);
  assert.match(script, /host\.shell\.updateLocal/);
  assert.match(manager, /KEY_MODE, Mode\.LOCAL\.value/);
  assert.match(manager, /downloadedVersion < embeddedVersion/);
  assert.match(manager, /bundleSha256/);
  assert.match(manager, /官网界面包摘要不匹配/);
  assert.match(manager, /store\/manifest\.json/);
  assert.match(manager, /suspend fun activateOnline/);
  assert.match(activity, /officialShell\.activateOnline\(\)/);
  assert.match(script, /shellVersionTaps < 3/);
});

test("shared WebView profile follows origin rules while Hermit data remains app scoped", () => {
  const activity = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/MainActivity.kt", "utf8");
  const registry = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/registry/AppRegistry.kt", "utf8");
  assert.doesNotMatch(activity, /WebViewCompat\.setProfile/);
  assert.match(activity, /"shared-web-message"/);
  assert.match(activity, /"shared-legacy-bridge"/);
  assert.match(activity, /"siteDataPolicy", "shared-by-origin"/);
  assert.match(registry, /icon_data_url/);
});

test("happ source and runtime mode remain independent across Native and HermitUI", () => {
  const model = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/model/WebAppInstance.kt", "utf8");
  const registry = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/registry/AppRegistry.kt", "utf8");
  const remote = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/install/RemoteSourceInstaller.kt", "utf8");
  const activity = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/MainActivity.kt", "utf8");
  const shell = fs.readFileSync("app/src/main/assets/store/store.js", "utf8");
  const backup = JSON.parse(fs.readFileSync("api/backup.schema.json", "utf8"));
  const install = JSON.parse(fs.readFileSync("api/hermit-install.schema.json", "utf8"));
  assert.match(model, /enum class HappSource \{ ONLINE, LOCAL \}/);
  assert.match(model, /enum class HappRuntimeMode \{ LOCAL, LIVE \}/);
  assert.match(registry, /source_kind TEXT NOT NULL/);
  assert.match(registry, /runtime_mode TEXT NOT NULL/);
  assert.match(registry, /private const val VERSION = 6/);
  assert.match(registry, /本地 happ 只能本地运行/);
  assert.match(remote, /encodedPath\("\/hermit-install\.json"\)/);
  assert.match(remote, /source = HappSource\.ONLINE/);
  assert.match(activity, /"host\.apps\.setRuntimeMode"/);
  assert.match(shell, /host\.apps\.setRuntimeMode/);
  assert.match(shell, /card\.dataset\.source/);
  assert.equal(backup.properties.schema.const, 2);
  assert.deepEqual(backup.properties.app.required.slice(0, 4), ["name", "source", "runtimeMode", "liveUrl"]);
  assert.equal(install.properties.schema.const, 1);
  assert.deepEqual(install.required, ["schema", "package"]);
});

test("domestic runtime baseline has local QR scanning and no Google Play service dependency", () => {
  const build = fs.readFileSync("app/build.gradle.kts", "utf8");
  const manifest = fs.readFileSync("app/src/main/AndroidManifest.xml", "utf8");
  const scanner = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/QrScannerActivity.kt", "utf8");
  const speech = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/capability/SpeechController.kt", "utf8");
  assert.doesNotMatch(build, /play-services|firebase|mlkit/i);
  assert.doesNotMatch(manifest, /com\.google\.mlkit|com\.google\.android\.gms/);
  assert.match(build, /androidx\.camera:camera-camera2:1\.5\.3/);
  assert.match(build, /com\.google\.zxing:core:3\.5\.4/);
  assert.match(scanner, /CameraX preview with an APK-local ZXing decoder/);
  assert.match(scanner, /POSSIBLE_FORMATS, listOf\(BarcodeFormat\.QR_CODE\)/);
  assert.match(speech, /Build\.VERSION\.SDK_INT >= Build\.VERSION_CODES\.S && SpeechRecognizer\.isOnDeviceRecognitionAvailable/);
});

test("basic microphone and speaker APIs are capability-gated and lifecycle-bound", () => {
  const caps = JSON.parse(fs.readFileSync("api/capabilities.json", "utf8"));
  const main = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/MainActivity.kt", "utf8");
  const audio = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/capability/AudioController.kt", "utf8");
  const types = fs.readFileSync("sdk/hermit-api.d.ts", "utf8");
  assert.deepEqual(caps.public.audio, ["startRecording", "stopRecording", "cancelRecording", "play", "stopPlayback"]);
  assert.match(main, /permissionBroker\.require\(session, "microphone\.record"/);
  assert.match(main, /audio\.cancelSession/);
  assert.match(audio, /FEATURE_MICROPHONE/);
  assert.match(audio, /FEATURE_AUDIO_OUTPUT/);
  assert.match(audio, /MAX_MAX_DURATION_MS = 30L \* 60_000/);
  assert.match(types, /microphone\.record/);
  assert.match(types, /startRecording/);
});
