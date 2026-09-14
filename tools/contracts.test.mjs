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

test("happ display policy is package-scoped and restored outside the happ", () => {
  const schema = JSON.parse(fs.readFileSync("api/hermit.schema.json", "utf8"));
  const manifest = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/install/PackageManifest.kt", "utf8");
  const activity = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/MainActivity.kt", "utf8");
  assert.deepEqual(schema.properties.display.properties.orientation.enum, ["unspecified", "portrait", "landscape"]);
  assert.deepEqual(schema.properties.display.properties.keyboard.enum, ["resize", "overlay"]);
  assert.match(manifest, /displayOrientation/);
  assert.match(manifest, /keyboardMode/);
  assert.match(activity, /SOFT_INPUT_ADJUST_NOTHING/);
  assert.match(activity, /SCREEN_ORIENTATION_PORTRAIT/);
  assert.match(activity, /if \(!keyboardOverlaysContent\) types = types or WindowInsetsCompat\.Type\.ime\(\)/);
  assert.match(activity, /showSupportBrowser\(\)[\s\S]*?applyDisplayPolicy\(null, forcePortrait = true\)/);
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
  assert.equal(new Set(tools.map(tool => tool.name)).size, 27);
  for (const tool of tools) {
    const dispatch = new RegExp(`(?:"[^"]+"\\s*,\\s*)*"${tool.name}"(?:\\s*,\\s*"[^"]+")*\\s*->`);
    assert.match(server, dispatch);
    assert.equal(tool.inputSchema.additionalProperties, false);
  }
  assert.doesNotMatch(server, /class Pairing|class Client|pairUrl|phone-approved/);
  assert.match(server, /commitGuard = \{ guarded\(authorization/);
  assert.match(server, /guardedValue\(authorization\) \{\s*devWorkspaces\.apply/);
  assert.equal(fs.readFileSync(root + "webapp-authoring.md", "utf8"), fs.readFileSync("docs/webapp-authoring.md", "utf8"));
  assert.equal(fs.readFileSync(root + "hermit-api.d.ts", "utf8"), fs.readFileSync("sdk/hermit-api.d.ts", "utf8"));
});

function shellSources(extension, directory = "app/src/main/assets/store") {
  return fs.readdirSync(directory, { withFileTypes: true }).map(entry => {
    const file = directory + "/" + entry.name;
    return entry.isDirectory() ? shellSources(extension, file) : file.endsWith(extension) ? fs.readFileSync(file, "utf8") : "";
  }).join("\n");
}

test("store navigation, favorites, support fallback and QR bridge remain wired", () => {
  const html = fs.readFileSync("app/src/main/assets/store/index.html", "utf8");
  const script = shellSources(".js");
  const css = shellSources(".css");
  const tabs = [...html.matchAll(/<nav class="bottom-nav[^"]*"[\s\S]*?<\/nav>/g)][0]?.[0] || "";
  assert.deepEqual([...tabs.matchAll(/data-view="([^"]+)"/g)].map(match => match[1]), ["favorites", "all", "development", "settings", "support"]);
  assert.equal((tabs.match(/class="nav-icon"/g) || []).length, 5);
  assert.match(css, /grid-template-columns:repeat\(5,minmax\(0,1fr\)\)/);
  assert.match(script, /"apps\.favorite"/);
  assert.match(script, /activeVersion/);
  assert.match(fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/MainActivity.kt", "utf8"), /\.put\("activeVersion"/);
  assert.match(script, /"apps\.scanQr"/);
  assert.match(script, /"support\.open"/);
  assert.match(html, /10knet·zhyuzh3d/);
  for (const id of ["addFolder", "addUrl", "scanQr", "topApkVersion", "topWebVersion"]) assert.match(html, new RegExp(`id="${id}"`));
  assert.match(script, /"apps\.pickDirectory"/);
  assert.match(script, /"apps\.confirmDirectory"/);
  assert.match(script, /"apps\.pickIcon"/);
  assert.match(css, /bottom-nav \.nav-icon\{margin:0 auto 4px!important\}/);
  assert.match(css, /\.agent-address-row \{ display:block;/);
  assert.match(css, /\.agent-url \{[^}]*white-space:nowrap;/);
  assert.match(css, /\.agent-secret button \{[^}]*align-self:flex-end;[^}]*height:48px;/);
});

test("package defaults and custom happ icon overrides stay distinct", () => {
  const activity = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/MainActivity.kt", "utf8");
  const installer = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/install/InstallCoordinator.kt", "utf8");
  const processor = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/install/IconProcessor.kt", "utf8");
  const registry = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/registry/AppRegistry.kt", "utf8");
  const shortcuts = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/launcher/ShortcutHost.kt", "utf8");
  assert.match(activity, /"host\.apps\.updatePresentation"/);
  assert.match(activity, /validateIconDataUrl\(params\.optString\("iconDataUrl"\)\)/);
  assert.match(activity, /put\("dataUrl", iconSourceDataUrl\(uri\)\)/);
  assert.match(installer, /IconProcessor\.centeredPngDataUrl/);
  assert.match(installer, /iconDataUrl = null/);
  assert.match(installer, /defaultIconDataUrl = packageIconDataUrl/);
  assert.match(installer, /registry\.updateDefaultIcon\(appId, packageIconDataUrl\)/);
  assert.match(processor, /OUTPUT_SIZE = 192/);
  assert.match(registry, /fun updatePresentation\(appId: String, name: String, iconDataUrl: String\?\)/);
  assert.match(registry, /put\("icon_data_url", iconDataUrl\)/);
  assert.match(registry, /put\("default_icon_data_url", defaultIconDataUrl\)/);
  assert.match(shortcuts, /instance\.effectiveIconDataUrl/);
  assert.match(shortcuts, /Icon::createWithBitmap/);
});

test("launcher uses the compressed Hermit brand icon while notifications keep a monochrome glyph", () => {
  const foreground = fs.readFileSync("app/src/main/res/drawable/ic_launcher_foreground.xml", "utf8");
  const notifications = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/notification/NotificationDispatcher.kt", "utf8");
  const icon = fs.statSync("app/src/main/res/drawable-nodpi/hermit_icon.webp");
  const colors = fs.readFileSync("app/src/main/res/values/colors.xml", "utf8");
  assert.match(foreground, /@drawable\/hermit_icon/);
  const appManifest = fs.readFileSync("app/src/main/AndroidManifest.xml", "utf8");
  assert.match(appManifest, /android:icon="@drawable\/hermit_icon"/);
  assert.doesNotMatch(foreground, /android:inset/);
  assert.match(notifications, /R\.drawable\.ic_notification/);
  assert.match(colors, /name="hermit_icon_background">#000000/);
  assert.ok(icon.size < 32 * 1024, `launcher icon is unexpectedly large: ${icon.size}`);
  assert.equal(fs.existsSync("app/src/main/res/drawable-night-nodpi/hermit_icon.webp"), false);
  assert.ok(fs.statSync("app/src/main/assets/store/assets/hermit-mark.svg").size < 2 * 1024);
});

test("official HermitUI is portrait-only while installed happs retain manifest orientation", () => {
  const activity = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/MainActivity.kt", "utf8");
  assert.match(activity, /applyDisplayPolicy\(packageManifest, forcePortrait = instance == null\)/);
  assert.match(activity, /forcePortrait -> ActivityInfo\.SCREEN_ORIENTATION_PORTRAIT/);
  assert.match(activity, /manifest\?\.displayOrientation == "landscape" -> ActivityInfo\.SCREEN_ORIENTATION_LANDSCAPE/);
});

test("online entry separates live pages from direct packages and install descriptors", () => {
  const installer = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/install/RemoteSourceInstaller.kt", "utf8");
  assert.match(installer, /encodedPath\.endsWith\("\.zip", ignoreCase = true\)/);
  assert.match(installer, /equals\("hermit-install\.json", ignoreCase = true\)/);
  assert.match(installer, /OnlineInstallResult\(app\.appId, null, "live", "live"\)/);
  assert.match(installer, /"online-descriptor"/);
});

test("official shell keeps live fallback support and explicit local refresh", () => {
  const activity = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/MainActivity.kt", "utf8");
  const manager = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/runtime/OfficialShellManager.kt", "utf8");
  const bridge = fs.readFileSync("app/src/main/assets/bridge/hermit-v1.js", "utf8");
  const html = fs.readFileSync("app/src/main/assets/store/index.html", "utf8");
  const script = shellSources(".js");
  assert.match(manager, /OFFICIAL_ORIGIN = "https:\/\/hermit\.10knet\.com"/);
  assert.match(manager, /ONLINE_URL = "\$OFFICIAL_ORIGIN\/shell\/index\.html"/);
  assert.match(manager, /Mode\.ONLINE/);
  assert.match(activity, /RuntimeRole\.STORE/);
  assert.match(activity, /LOAD_NO_CACHE/);
  assert.match(activity, /正在加载官网界面/);
  assert.match(activity, /local-fallback/);
  assert.match(bridge, /shell: namespace\("host\.shell"\)/);
  for (const id of ["shellVersionSwitch", "updateLocalShell"]) assert.match(html, new RegExp(`id="${id}"`));
  assert.doesNotMatch(script, /"shell\.setMode"/);
  assert.match(script, /"shell\.updateLocal"/);
  assert.match(manager, /KEY_MODE, Mode\.LOCAL\.value/);
  assert.match(manager, /downloadedVersion < embeddedVersion/);
  assert.match(manager, /bundleSha256/);
  assert.match(manager, /官网界面包摘要不匹配/);
  assert.match(manager, /store\/manifest\.json/);
  assert.match(manager, /suspend fun activateOnline/);
  assert.match(activity, /officialShell\.activateOnline\(\)/);
  assert.doesNotMatch(html, /id="useOnlineShell"/);
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
  const shell = shellSources(".js");
  const caps = JSON.parse(fs.readFileSync("api/capabilities.json", "utf8"));
  const backup = JSON.parse(fs.readFileSync("api/backup.schema.json", "utf8"));
  const install = JSON.parse(fs.readFileSync("api/hermit-install.schema.json", "utf8"));
  assert.match(model, /enum class HappSource \{ ONLINE, LOCAL \}/);
  assert.match(model, /enum class HappRuntimeMode \{ LOCAL, LIVE \}/);
  assert.match(registry, /source_kind TEXT NOT NULL/);
  assert.match(registry, /runtime_mode TEXT NOT NULL/);
  assert.match(registry, /private const val VERSION = 9/);
  assert.match(registry, /current\.activeReleaseId/);
  assert.match(remote, /encodedPath\("\/hermit-install\.json"\)/);
  assert.match(remote, /downloadSameOrigin\(packageUrl, updateUrl\)/);
  assert.match(remote, /recordDownload\(result\.appId, packageUrl/);
  assert.doesNotMatch(remote, /readNBytes/);
  assert.match(remote, /source = HappSource\.ONLINE/);
  assert.match(activity, /"host\.apps\.setRuntimeMode"/);
  assert.match(activity, /"app\.setRuntimeMode"[\s\S]*requireApp\(app\)[\s\S]*registry\.setRuntimeMode\(target\.appId, requested\)/);
  assert.ok(caps.public.app.includes("setRuntimeMode"));
  assert.match(fs.readFileSync("sdk/hermit-api.d.ts", "utf8"), /setRuntimeMode\(params: \{ runtimeMode: "local" \| "live" \}\)/);
  assert.match(shell, /"apps\.setRuntimeMode"/);
  assert.match(shell, /"apps\.updateUrls"/);
  assert.match(shell, /card\.dataset\.source/);
  assert.equal(backup.properties.schema.const, 2);
  assert.deepEqual(backup.properties.app.required.slice(0, 4), ["name", "source", "runtimeMode", "liveUrl"]);
  assert.equal(install.properties.schema.const, 1);
  assert.deepEqual(install.required, ["schema", "package"]);
});

test("notifications stay native, per-happ and do not execute background page code", () => {
  const caps = JSON.parse(fs.readFileSync("api/capabilities.json", "utf8"));
  const activity = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/MainActivity.kt", "utf8");
  const worker = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/notification/OnlineNotificationWorker.kt", "utf8");
  const scheduler = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/notification/NotificationScheduler.kt", "utf8");
  assert.deepEqual(caps.public.notifications, ["notify", "schedule", "cancel", "cancelAll", "getScheduled", "setEndpoint", "getStatus"]);
  assert.match(activity, /permissionBroker\.require\(runtime, "notifications"/);
  assert.match(scheduler, /setExactAndAllowWhileIdle/);
  assert.match(worker, /notify-token/);
  assert.doesNotMatch(worker, /readNBytes/);
  assert.doesNotMatch(worker, /WebView|evaluateJavascript/);
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
  assert.match(audio, /"audio\.recording\.level"/);
  assert.match(audio, /maxAmplitude/);
  assert.match(types, /microphone\.record/);
  assert.match(types, /startRecording/);
});

test("native HTTP supports session-bound incremental reads and logical file uploads", () => {
  const caps = JSON.parse(fs.readFileSync("api/capabilities.json", "utf8"));
  const main = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/MainActivity.kt", "utf8");
  const network = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/capability/NativeHttpClient.kt", "utf8");
  const bridge = fs.readFileSync("app/src/main/assets/bridge/hermit-v1.js", "utf8");
  const types = fs.readFileSync("sdk/hermit-api.d.ts", "utf8");
  assert.deepEqual(caps.public.network, ["status", "watch", "clearWatch", "request", "openStream", "readStream", "closeStream", "openSocket", "readSocket", "sendSocket", "closeSocket"]);
  assert.match(main, /nativeHttp\.openStream\(session\.sessionId/);
  assert.match(main, /nativeHttp\.closeAll\(it\)/);
  assert.match(network, /bodyLogicalFileId/);
  assert.match(network, /MultipartBody\.Builder/);
  assert.match(network, /MAX_STREAM_CHUNK = 64 \* 1024/);
  assert.match(network, /handle\.owner != owner/);
  assert.match(network, /openSocket\(/);
  assert.match(network, /MAX_SOCKET_FRAME_BYTES = 1024 \* 1024/);
  assert.match(network, /ownedSocket\(socketId, owner, appId, generation\)/);
  assert.match(bridge, /requestTimeout\(method, params\)/);
  assert.match(types, /interface HermitNetworkStream/);
  assert.match(types, /interface HermitNetworkSocket/);
});

test("system capability adapters are discoverable, permission-gated and foreground-bound", () => {
  const caps = JSON.parse(fs.readFileSync("api/capabilities.json", "utf8"));
  const manifest = fs.readFileSync("app/src/main/AndroidManifest.xml", "utf8");
  const main = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/MainActivity.kt", "utf8");
  const bridge = fs.readFileSync("app/src/main/assets/bridge/hermit-v1.js", "utf8");
  const types = fs.readFileSync("sdk/hermit-api.d.ts", "utf8");
  assert.equal(caps.apiMinor, 10);
  assert.deepEqual(caps.public.tts, ["availability", "preferences", "voices", "languageAvailability", "speak", "stop", "export"]);
  assert.deepEqual(caps.public.speech, ["availability", "preferences", "languages", "start", "recognizeOnce", "stop", "cancel"]);
  assert.deepEqual(caps.storeOnly["host.voice"], ["status", "ttsVoices", "configure", "testTts", "openSettings"]);
  assert.deepEqual(caps.public.sensors, ["availability", "watch", "clearWatch"]);
  assert.deepEqual(caps.public.wifi, ["status", "scan", "requestNetwork", "releaseNetwork", "openSettings"]);
  assert.ok(caps.public.bluetooth.includes("subscribe"));
  assert.deepEqual(caps.public.infrared, ["status", "transmit"]);
  for (const namespace of ["sensors", "wifi", "bluetooth", "infrared", "battery", "system"]) {
    assert.match(bridge, new RegExp(`${namespace}: namespace\\("${namespace}"\\)`));
    assert.match(types, new RegExp(`\\b${namespace}:`));
  }
  for (const permission of ["ACTIVITY_RECOGNITION", "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "NEARBY_WIFI_DEVICES", "TRANSMIT_IR"]) {
    assert.match(manifest, new RegExp(`android\\.permission\\.${permission}`));
  }
  assert.match(main, /permissionBroker\.require\(session, "wifi\.scan"/);
  assert.match(main, /permissionBroker\.require\(session, "bluetooth\.connect"/);
  assert.match(main, /permissionBroker\.require\(session, "infrared\.transmit"/);
  assert.match(main, /authorizationDescriptors\(runtime, name\)/);
  assert.match(types, /authorization\?: Record/);
  assert.match(main, /sensors\.cancelAll\(\)/);
  assert.match(main, /bluetooth\.cancelAll\(\)/);
  const speech = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/capability/SpeechController.kt", "utf8");
  const tts = fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/capability/TtsController.kt", "utf8");
  assert.match(speech, /SpeechRecognizer\.createSpeechRecognizer\(appContext, selection\.component\)/);
  assert.match(speech, /speech\.recognizeOnce/);
  assert.match(speech, /RecognizerIntent\.ACTION_GET_LANGUAGE_DETAILS/);
  assert.match(speech, /EXTRA_SUPPORTED_LANGUAGES/);
  assert.match(main, /"speech" -> speech\.availability\(\)\.optBoolean\("available"\)/);
  assert.match(main, /"speech\.languages" -> speech\.languages\(\)/);
  assert.match(tts, /TextToSpeech\(appContext, \{ ready\.complete\(it\) \}, engineId\)/);
  assert.match(tts, /availableLanguages/);
  assert.doesNotMatch(tts, /tts\.providerFallback/);
  assert.doesNotMatch(types, /providerManagedByUser|directServiceAvailable|recognitionActivityAvailable|\bengines\(\):/);
  assert.match(fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/capability/SensorController.kt", "utf8"), /MAX_RATE_HZ = 60\.0/);
  assert.match(fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/capability/WifiController.kt", "utf8"), /directSavedNetworkChangesAllowed", false/);
  assert.match(fs.readFileSync("app/src/main/java/io/github/zhyuzh3d/hermit/capability/InfraredController.kt", "utf8"), /MAX_TOTAL_US = 2_000_000L/);
});
